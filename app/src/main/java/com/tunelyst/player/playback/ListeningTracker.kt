package com.tunelyst.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.concurrent.Executors

/** Records listening on the playback service, including while the UI is closed. */
class ListeningTracker(context: Context, private val player: Player) : Player.Listener {
    private val appContext = context.applicationContext
    private val writer = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var trackId: Long? = null
    private var playedMs = 0L
    private var runningSince: Long? = null
    private var recordedPlay = false
    private var closed = false
    private var crossfadeNext: Pair<Int, String?>? = null
    private var crossfadeDeadline = 0L
    internal fun expectCrossfade(index: Int, mediaId: String?) { crossfadeNext = index to mediaId; crossfadeDeadline = SystemClock.elapsedRealtime() + 2000 }
    internal fun clearExpectedCrossfade() { crossfadeNext = null; crossfadeDeadline = 0L }

    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            checkpoint()
            recordQualifiedPlay()
            handler.postDelayed(this, 2000)
        }
    }

    init {
        trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (player.isPlaying) runningSince = SystemClock.elapsedRealtime()
        player.addListener(this)
        handler.postDelayed(ticker, 2000)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        checkpoint()
        recordQualifiedPlay()
        runningSince = if (isPlaying) SystemClock.elapsedRealtime() else null
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            checkpoint()
            if (playedMs >= 2000) recordPlay()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Updating current item's notification artwork is not a new play.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem?.mediaId?.toLongOrNull() == trackId) return
        checkpoint()
        val planned = crossfadeNext
        val crossfade = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && planned != null &&
            SystemClock.elapsedRealtime() <= crossfadeDeadline && planned.first == player.currentMediaItemIndex && planned.second == mediaItem?.mediaId
        clearExpectedCrossfade()
        // Crossfades are scheduled natural transitions, not user skips.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || crossfade) {
            if (playedMs >= 2000) recordPlay()
        } else {
            recordQualifiedPlay()
            // Only explicit item jumps before 30 seconds are early skips; ignore queue replacement.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && playedMs in 2000 until 30000 && !recordedPlay) recordSkip()
        }
        trackId = mediaItem?.mediaId?.toLongOrNull()
        playedMs = 0
        recordedPlay = false
        runningSince = if (player.isPlaying) SystemClock.elapsedRealtime() else null
    }

    private fun checkpoint() {
        val now = SystemClock.elapsedRealtime()
        runningSince?.let { playedMs += (now - it).coerceAtLeast(0) }
        if (runningSince != null) runningSince = now
    }
    private fun recordQualifiedPlay() { if (playedMs >= 30000) recordPlay() }
    private fun recordPlay() {
        val id = trackId ?: return
        if (recordedPlay) return
        recordedPlay = true
        val timestamp = System.currentTimeMillis()
        writer.execute { LibraryDb(appContext).use { it.recordPlay(id, timestamp) } }
    }
    private fun recordSkip() {
        val id = trackId ?: return
        writer.execute { LibraryDb(appContext).use { it.recordSkip(id) } }
    }
    fun release() {
        if (closed) return
        checkpoint(); recordQualifiedPlay()
        closed = true
        handler.removeCallbacks(ticker)
        player.removeListener(this)
        writer.shutdown()
    }
}
