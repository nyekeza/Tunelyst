package com.tunelyst.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Service-owned timer. End-of-song follows the current item even after a manual skip. */
internal class SleepTimerController(private val player: ExoPlayer, private val suppressCrossfade: (Boolean) -> Unit,
    private val alarm: (Long) -> Unit, private val cancelAlarm: () -> Unit) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var endOfSong = false
    private var fade = false
    private var savedVolume: Float? = null
    private var closing = false
    private val tick = object : Runnable {
        override fun run() {
            if (deadline == 0L && !endOfSong) return
            val remaining = if (endOfSong) {
                if (!player.isPlaying || player.duration == C.TIME_UNSET || player.duration <= 0) Long.MAX_VALUE
                else ((player.duration - player.currentPosition).coerceAtLeast(0) / player.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
            } else deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) { finish(); return }
            if (fade && remaining <= FADE_MS && player.isPlaying) {
                if (savedVolume == null) { suppressCrossfade(true); savedVolume = player.volume }
                player.volume = (savedVolume!! * (remaining.toFloat() / FADE_MS)).coerceIn(0f, 1f)
            } else if (savedVolume != null && (!player.isPlaying || remaining > FADE_MS)) {
                restoreVolume()
                if (!endOfSong) suppressCrossfade(false)
            }
            handler.postDelayed(this, if (remaining <= FADE_MS + 500 && (fade || endOfSong)) 100 else minOf(remaining, 1000L))
        }
    }
    init { player.addListener(this) }
    fun set(duration: Long, end: Boolean, fadeOut: Boolean): Boolean {
        if (duration < 0 || duration > 24L * 60 * 60 * 1000 || (end && player.currentMediaItem == null)) return false
        cancel(); if (duration == 0L && !end) return true
        deadline = if (end) 0 else SystemClock.elapsedRealtime() + duration
        endOfSong = end; fade = fadeOut
        if (end) { suppressCrossfade(true); player.setPauseAtEndOfMediaItems(true) }
        else alarm(deadline)
        handler.post(tick); return true
    }
    fun wake() { handler.removeCallbacks(tick); handler.post(tick) }
    private fun restoreVolume() { savedVolume?.let { player.volume = it }; savedVolume = null }
    private fun finish() {
        if (closing) return
        closing = true; player.pause(); cancel(); closing = false
    }
    fun cancel() {
        handler.removeCallbacks(tick); cancelAlarm(); deadline = 0; endOfSong = false; fade = false
        player.setPauseAtEndOfMediaItems(false); restoreVolume(); suppressCrossfade(false)
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (endOfSong && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) finish()
    }
    override fun onPlaybackStateChanged(playbackState: Int) { if (endOfSong && playbackState == Player.STATE_ENDED) finish() }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (endOfSong && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) finish()
    }
    fun release() { cancel(); player.removeListener(this) }
    companion object { const val FADE_MS = 10_000L }
}
