package com.tunelyst.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors

/** Saves queue state without holding the audio service's main thread on disk IO. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackPersistence internal constructor(context: Context, private val player: ExoPlayer,
    private val scanLibrary: (Context) -> List<Track>, private val lookup: ((Context, List<Long>) -> List<Track>?)?) : Player.Listener {
    constructor(context: Context, player: ExoPlayer) : this(context, player, LibraryScanCoordinator::scan, QueueRecoveryLookup::find)
    /** Injecting a scan function preserves deterministic recovery tests and fallback behavior. */
    constructor(context: Context, player: ExoPlayer, scanLibrary: (Context) -> List<Track>) : this(context, player, scanLibrary, null)
    private val app = context.applicationContext
    private val store = PlaybackStateStore(app)
    private val writer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var restoring = false
    private var retryPending = false
    private var preserveUnresolved = false
    private var closed = false
    private val saveTask = Runnable { saveNow() }
    private val ticker = object : Runnable {
        override fun run() { if (!closed) { if (player.isPlaying) saveNow(); main.postDelayed(this, 5_000) } }
    }
    init {
        player.addListener(this)
        beginRestore()
        main.postDelayed(ticker, 5_000)
    }
    /** Called when media permission or an unavailable storage volume returns. */
    fun retryRestore() {
        if (closed || player.mediaItemCount != 0) return
        if (restoring) retryPending = true
        else if (preserveUnresolved) beginRestore()
    }
    private fun beginRestore() {
        if (closed || restoring) return
        restoring = true
        writer.execute {
            val state = store.load()
            val library = if (state != null) runCatching {
                lookup?.invoke(app, state.ids) ?: scanLibrary(app)
            }.getOrNull() else null
            val resolved = if (state != null && library != null) store.resolveQueue(state, library) else null
            main.post {
                if (!closed) {
                    // A queue chosen by the user while scanning always wins.
                    if (state != null && resolved != null && player.mediaItemCount == 0) {
                        val songs = resolved.songs; val index = resolved.index
                        val pos = if (songs[index].id == state.ids[state.index]) state.positionMs else 0L
                        player.setMediaItems(songs.mapIndexed { i, track -> track.item(resolved.metadata.getOrNull(i)?.first, resolved.metadata.getOrNull(i)?.second ?: false, resolved.queueOrigins.getOrNull(i)) }, index, pos)
                        player.repeatMode = state.repeatMode
                        val order = resolved.shuffleOrder
                        if (order != null && order.size == songs.size) {
                            player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
                            player.shuffleModeEnabled = true
                        }
                        player.prepare(); player.pause()
                    }
                    preserveUnresolved = state != null && resolved == null && player.mediaItemCount == 0
                    restoring = false
                    val retry = retryPending && preserveUnresolved
                    retryPending = false
                    if (player.mediaItemCount > 0) scheduleSave()
                    else if (retry) beginRestore()
                }
            }
        }
    }
    override fun onEvents(player: Player, events: Player.Events) { if (!restoring) scheduleSave() }
    private fun scheduleSave() { main.removeCallbacks(saveTask); main.postDelayed(saveTask, 1000) }
    private fun saveNow() {
        if (closed || restoring) return
        if (preserveUnresolved && player.mediaItemCount == 0) return
        preserveUnresolved = false
        if ((0 until player.mediaItemCount).any { SharedAudio.isShared(player.getMediaItemAt(it)) }) {
            // Temporary sender permissions must not cause an old queue to reappear after a restart.
            writer.execute { runCatching { store.clear() } }
            return
        }
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (items.any { it == null }) return
        val timeline = player.currentTimeline
        val order = if (player.shuffleModeEnabled && !timeline.isEmpty) {
            val indices = mutableListOf<Int>(); val seen = HashSet<Int>(); var i = timeline.getFirstWindowIndex(true)
            while (i != androidx.media3.common.C.INDEX_UNSET && seen.add(i) && indices.size < items.size) {
                indices.add(i); i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
            indices.takeIf { it.size == items.size }
        } else null
        val signatures = (0 until player.mediaItemCount).map { i ->
            val m = player.getMediaItemAt(i).mediaMetadata
            PlaybackStateStore.signature(m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.albumTitle?.toString().orEmpty())
        }
        val sources = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).mediaMetadata.extras?.getString("tunelyst.source") }
        val suggestions = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).mediaMetadata.extras?.getBoolean("tunelyst.suggested") == true }
        val origins = (0 until player.mediaItemCount).map { i -> QueuePriority.origin(player.getMediaItemAt(i)) }
        val snapshot = SavedQueue(items.filterNotNull(), player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.repeatMode, order, signatures, sources, suggestions, origins)
        writer.execute { runCatching { store.save(snapshot) } }
    }
    fun release() {
        if (closed) return
        main.removeCallbacks(saveTask); main.removeCallbacks(ticker)
        if (restoring && player.mediaItemCount > 0) restoring = false
        saveNow(); closed = true
        player.removeListener(this); writer.shutdown()
    }
}
