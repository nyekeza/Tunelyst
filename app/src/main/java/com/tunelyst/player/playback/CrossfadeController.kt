package com.tunelyst.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlin.math.abs

/**
 * Opt-in overlap. A muted player tracks the outgoing item so an automatic or
 * manual switch can keep its tail while the session player starts the next item.
 * If synchronization fails, the original player command is still executed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CrossfadeController(context: Context, private val player: ExoPlayer, private val tracker: ListeningTracker) : Player.Listener {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(CrossfadePreferences.PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var tail: ExoPlayer? = null
    private var outgoingIndex = -1
    private var outgoingId: String? = null
    private var nextIndex = -1
    private var nextId: String? = null
    private var originalVolume = 1f
    private var fading = false
    private var starting = false
    private var fadeStartedAt = -1L
    private var waitingSince = 0L
    private var fadeDuration = 0L
    private var retryAfter = 0L
    private var lastResyncAt = 0L
    private val maxHandoffDriftMs = 30L
    private var shareAudioSession = true
    private var closed = false
    private var suppressed = false
    private data class PendingSwitch(
        val targetIndex: Int, val targetId: String, val sourceIndex: Int, val sourceId: String?,
        val deadline: Long, val command: () -> ListenableFuture<*>, val result: SettableFuture<Unit>
    )
    private var pending: PendingSwitch? = null
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (CrossfadePreferences.affects(key)) {
            if (CrossfadePreferences.duration(app, false) == 0) finishPending(execute = true)
            cancel(); retryAfter = 0L; schedule()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            runCatching { step() }.onFailure { error ->
                Log.w("TunelystCrossfade", "Crossfade preparation or update failed; normal playback continues", error)
                if (tail != null && shareAudioSession) shareAudioSession = false
                cancel(); retryAfter = SystemClock.elapsedRealtime() + 1000
            }
            schedule()
        }
    }
    init {
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        player.addListener(this)
        schedule()
    }
    private fun schedule() {
        handler.removeCallbacks(tick)
        if (!closed && !suppressed && (CrossfadePreferences.duration(app, true) > 0 || CrossfadePreferences.duration(app, false) > 0) && (player.isPlaying || fading || pending != null)) handler.postDelayed(tick, 40)
    }
    private fun findNext(): Int {
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return C.INDEX_UNSET
        val mode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        return timeline.getNextWindowIndex(index, mode, player.shuffleModeEnabled)
    }
    private fun validNext(index: Int): Boolean = index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex
    private fun canPrepare(): Boolean = player.isPlaying && player.playbackParameters.speed.isFinite() && player.playbackParameters.speed > 0f &&
        !player.isPlayingAd && player.currentMediaItem != null && player.duration > 0
    private fun step() {
        if (CrossfadePreferences.duration(app, true) == 0 && CrossfadePreferences.duration(app, false) == 0) { finishPending(execute = true); cancel(); return }
        val waiting = pending
        if (waiting != null) {
            if (player.currentMediaItemIndex != waiting.sourceIndex || player.currentMediaItem?.mediaId != waiting.sourceId) {
                finishPending(execute = false); cancel(); return
            }
            if (SystemClock.elapsedRealtime() >= waiting.deadline || !canPrepare() || CrossfadePreferences.duration(app, false) == 0) {
                finishPending(execute = true); cancel(); return
            }
        }
        if (fading) { stepFade(); return }
        if (!canPrepare()) { cancel(); return }
        if (tail == null) {
            if (SystemClock.elapsedRealtime() < retryAfter) return
            val item = player.currentMediaItem ?: return
            outgoingIndex = player.currentMediaItemIndex; outgoingId = item.mediaId
            originalVolume = player.volume
            val second = ExoPlayer.Builder(app).build(); tail = second
            second.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            second.setHandleAudioBecomingNoisy(false)
            // Some devices cannot open a second AudioTrack in the same session.
            // Retry with its own session on a decoder/audio-output failure.
            if (shareAudioSession && player.audioSessionId > 0) second.setAudioSessionId(player.audioSessionId)
            second.volume = 0f; second.setMediaItem(item); second.prepare(); second.seekTo(player.currentPosition); second.play()
            lastResyncAt = SystemClock.elapsedRealtime()
            return
        }
        val second = tail ?: return
        if (player.currentMediaItemIndex != outgoingIndex || player.currentMediaItem?.mediaId != outgoingId) { cancel(); return }
        if (second.playerError != null) {
            Log.w("TunelystCrossfade", "Secondary player failed; retrying without shared audio session", second.playerError)
            if (shareAudioSession) shareAudioSession = false
            cancel(); retryAfter = SystemClock.elapsedRealtime() + 1000; return
        }
        if (!second.isPlaying || second.playbackState != Player.STATE_READY) return
        originalVolume = player.volume
        val delta = second.currentPosition - player.currentPosition
        val now = SystemClock.elapsedRealtime()
        // A second decoder can take hundreds of milliseconds to become ready. Repeatedly
        // seeking it just restarts that delay. Let the inaudible copy catch up quickly,
        // and reserve seeking for large drift with a cooldown.
        if (abs(delta) > 2000 && now - lastResyncAt > 3000) {
            second.seekTo(player.currentPosition); lastResyncAt = now
        } else {
            val factor = when {
                delta < -150 -> 1.50f
                delta < -30 -> 1.25f
                delta < -10 -> 1.10f
                delta > 150 -> 0.50f
                delta > 30 -> 0.75f
                delta > 10 -> 0.90f
                else -> 1f
            }
            val speed = (player.playbackParameters.speed * factor).coerceIn(0.1f, 8f)
            if (abs(second.playbackParameters.speed - speed) > 0.001f) second.setPlaybackSpeed(speed)
        }
        val manual = pending
        if (manual != null) {
            if (abs(delta) <= maxHandoffDriftMs) {
                pending = null
                val remaining = ((player.duration - player.currentPosition).coerceAtLeast(0) / player.playbackParameters.speed).toLong()
                val ms = minOf(CrossfadePreferences.duration(app, false).toLong(), remaining)
                if (ms > 100) completePending(manual) { start(manual.targetIndex, manual.targetId, ms, automatic = false, command = manual.command) }
                else completePending(manual, manual.command)
            }
            return // A manual request takes precedence over automatic transition timing.
        }
        val next = findNext()
        val overlap = CrossfadePolicy.overlapMs(CrossfadePreferences.duration(app, true), player.duration,
            player.playbackParameters.speed, player.repeatMode == Player.REPEAT_MODE_ONE, validNext(next))
        val remaining = ((player.duration - player.currentPosition).coerceAtLeast(0) / player.playbackParameters.speed).toLong()
        if (overlap > 0 && remaining <= overlap + 40 && remaining > 40 && abs(delta) <= maxHandoffDriftMs) {
            val targetId = player.getMediaItemAt(next).mediaId
            start(next, targetId, minOf(overlap, remaining), automatic = true) { player.seekTo(next, 0L) }
        }
    }
    /**
     * Hold the old track briefly if its muted copy is still preparing or syncing.
     * Bounded waiting avoids silently discarding every early Next/Previous fade.
     * The original command is always executed once or explicitly superseded.
     */
    fun manualSwitch(targetIndex: Int, targetId: String, command: () -> ListenableFuture<*>): ListenableFuture<*> {
        if (suppressed) return command()
        // Preserve consecutive skip semantics: the first request moves the base player
        // before forwarding a second request that was computed from the facade state.
        if (pending != null) { finishPending(execute = true); cancel() }
        if (fading) cancel()
        val ms = CrossfadePreferences.duration(app, false)
        val second = tail
        val remaining = ((player.duration - player.currentPosition).coerceAtLeast(0) / player.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
        if (ms <= 0 || !canPrepare() || remaining <= 100) { cancel(); return command() }
        val ready = second != null && second.playerError == null && second.isPlaying &&
            second.playbackState == Player.STATE_READY && outgoingIndex == player.currentMediaItemIndex &&
            outgoingId == player.currentMediaItem?.mediaId && abs(second.currentPosition - player.currentPosition) <= maxHandoffDriftMs
        if (ready) return start(targetIndex, targetId, minOf(ms.toLong(), remaining), automatic = false, command = command)
        // A failed or stale copy cannot become synchronized; rebuild it on the next tick.
        if (second != null && (second.playerError != null || outgoingIndex != player.currentMediaItemIndex || outgoingId != player.currentMediaItem?.mediaId)) cancel()
        val completion = SettableFuture.create<Unit>()
        // Never wait beyond the outgoing track's remaining audible time.
        val waitMs = minOf(1800L, (remaining - 100).coerceAtLeast(0))
        pending = PendingSwitch(targetIndex, targetId, player.currentMediaItemIndex, player.currentMediaItem?.mediaId,
            SystemClock.elapsedRealtime() + waitMs, command, completion)
        schedule()
        return completion
    }
    private fun completePending(item: PendingSwitch, action: () -> ListenableFuture<*>) {
        try {
            val future = action()
            future.addListener({
                try { future.get(); item.result.set(Unit) }
                catch (error: java.util.concurrent.ExecutionException) { item.result.setException(error.cause ?: error) }
                catch (error: Exception) { item.result.setException(error) }
            }, MoreExecutors.directExecutor())
        } catch (error: Exception) { item.result.setException(error) }
    }
    /** Resolve a queued navigation before interpreting another relative seek. */
    fun finishPendingNavigation() { if (pending != null) { finishPending(execute = true); cancel() } }
    private fun finishPending(execute: Boolean) {
        val item = pending ?: return
        pending = null
        if (execute) completePending(item, item.command) else item.result.set(Unit)
    }
    private fun <T> start(index: Int, id: String, duration: Long, automatic: Boolean, command: () -> T): T {
        val second = tail ?: return command()
        // Initialization failures must not prevent the requested track change.
        try {
            second.setPlaybackSpeed(player.playbackParameters.speed)
            nextIndex = index; nextId = id; fadeDuration = duration.coerceAtLeast(1)
            waitingSince = SystemClock.elapsedRealtime(); fadeStartedAt = -1L
            starting = true; fading = true; originalVolume = player.volume
            if (automatic) tracker.expectCrossfade(index, id)
            second.volume = originalVolume; player.volume = 0f
        } catch (error: Exception) {
            Log.w("TunelystCrossfade", "Unable to start overlap; switching normally", error)
            cancel()
            return command()
        }
        return try { command() } catch (error: Throwable) { cancel(); throw error } finally { starting = false; schedule() }
    }
    private fun stepFade() {
        val second = tail ?: run { cancel(); return }
        if (player.currentMediaItemIndex != nextIndex || player.currentMediaItem?.mediaId != nextId || second.playerError != null || !second.isPlaying) { cancel(); return }
        if (!player.isPlaying) {
            val waitingForIncoming = fadeStartedAt < 0 && player.playWhenReady &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) &&
                SystemClock.elapsedRealtime() - waitingSince <= 2000
            if (!waitingForIncoming) cancel()
            return
        }
        if (fadeStartedAt < 0) {
            val tailRemaining = ((second.duration - second.currentPosition).coerceAtLeast(0) / second.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
            if (tailRemaining <= 0) { cancel(); return }
            fadeDuration = minOf(fadeDuration, tailRemaining).coerceAtLeast(1)
            fadeStartedAt = SystemClock.elapsedRealtime()
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAt
        val (out, incoming) = CrossfadePolicy.gains(elapsed, fadeDuration)
        second.volume = (originalVolume * out).coerceIn(0f, 1f)
        player.volume = (originalVolume * incoming).coerceIn(0f, 1f)
        if (elapsed >= fadeDuration) cancel()
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val waiting = fading && fadeStartedAt < 0 && player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE)
        if (!isPlaying && !starting && !waiting) { finishPending(execute = true); cancel() }
        schedule()
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val ownTransition = fading && player.currentMediaItemIndex == nextIndex && mediaItem?.mediaId == nextId &&
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        if (!starting && !ownTransition) { finishPending(execute = false); if (tail != null) cancel() }
        schedule()
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val initialHandoff = fading && oldPosition.mediaItemIndex == outgoingIndex && newPosition.mediaItemIndex == nextIndex &&
            (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_REMOVE)
        // ExoPlayer can follow a seek into another item with a second, same-item
        // SEEK_ADJUSTMENT when its actual sync point is known. It is not a new
        // user seek and must not cancel the overlap we just started.
        val seekAdjustment = fading && reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT &&
            oldPosition.mediaItemIndex == nextIndex && newPosition.mediaItemIndex == nextIndex &&
            player.currentMediaItemIndex == nextIndex && player.currentMediaItem?.mediaId == nextId
        if (!starting && !initialHandoff && !seekAdjustment) { finishPending(execute = false); if (tail != null) cancel() }
        schedule()
    }
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { if (fading) cancel(); schedule() }
    override fun onRepeatModeChanged(repeatMode: Int) { if (pending != null) { finishPending(execute = true); cancel() }; schedule() }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { if (pending != null) { finishPending(execute = true); cancel() }; schedule() }
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { cancel() }
    override fun onAudioSessionIdChanged(audioSessionId: Int) { if (tail != null) cancel(); schedule() }
    private fun cancel() {
        if (tail != null || fading) {
            runCatching { tail?.release() }; tail = null
            if (fading) runCatching { player.volume = originalVolume.coerceIn(0f, 1f) }
        }
        tracker.clearExpectedCrossfade()
        fading = false; starting = false; fadeStartedAt = -1L; outgoingIndex = -1; outgoingId = null; nextIndex = -1; nextId = null
    }
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) { finishPending(execute = true); cancel() }
        schedule()
    }
    fun release() {
        if (closed) return
        closed = true; handler.removeCallbacks(tick); prefs.unregisterOnSharedPreferenceChangeListener(changeListener)
        player.removeListener(this); finishPending(execute = false); cancel()
    }
}
