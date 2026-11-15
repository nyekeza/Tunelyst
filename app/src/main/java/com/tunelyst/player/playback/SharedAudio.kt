package com.tunelyst.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.UUID
import java.util.concurrent.Executors

/** Temporary incoming content; never stores or copies the sender's file. */
internal object SharedAudio {
    const val KEY = "tunelyst.shared_audio"
    fun isShared(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(KEY) == true
    fun item(uri: android.net.Uri, title: String): MediaItem = MediaItem.Builder()
        .setMediaId("shared:${UUID.randomUUID()}").setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Shared audio")
            .setExtras(Bundle().apply { putBoolean(KEY, true); putString("tunelyst.source", "Shared audio") }).build()).build()
}

/** After a naturally completed shared queue, start an offline weighted shuffle in the service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SharedAudioContinuation(context: Context, private val player: ExoPlayer,
    private val scan: (Context) -> List<Track> = Library::scan) : Player.Listener {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var requestedId: String? = null
    private var closed = false
    init { player.addListener(this) }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { generation++; requestedId = null }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) { generation++; requestedId = null }
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) { generation++; return }
        val item = player.currentMediaItem ?: return
        if (!SharedAudio.isShared(item) || !player.playWhenReady || requestedId == item.mediaId) return
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return
        requestedId = item.mediaId
        val ticket = ++generation
        worker.execute {
            val items = runCatching {
                val library = LibraryFilters(app).apply(scan(app)).distinctBy { it.id }
                val (liked, stats) = LibraryDb(app).use { it.favorites() to it.listeningStats() }
                SmartShuffle.order(library, liked, stats, weights = SmartShufflePreferences(app).get()).map { it.item("Your library") }
            }.getOrDefault(emptyList())
            main.post {
                if (!closed && ticket == generation && player.currentMediaItem?.mediaId == item.mediaId &&
                    player.playbackState == Player.STATE_ENDED && player.playWhenReady && items.isNotEmpty()) {
                    player.setMediaItems(items, 0, 0)
                    player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(IntArray(items.size) { it }, System.nanoTime()))
                    player.shuffleModeEnabled = true
                    player.prepare(); player.play()
                }
            }
        }
    }
    fun release() { closed = true; generation++; player.removeListener(this); worker.shutdown() }
}
