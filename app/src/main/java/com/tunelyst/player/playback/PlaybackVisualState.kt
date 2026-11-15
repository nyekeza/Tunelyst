package com.tunelyst.player

import android.os.SystemClock
import androidx.media3.common.Player

/** Read-only UI state. The service's player is weakly referenced and read on its main application thread. */
internal object PlaybackVisualState {
    data class Preview(
        val mediaId: String, val title: String?, val artist: String?, val shared: Boolean,
        val sharedArtwork: ByteArray?, val playing: Boolean, val shuffle: Boolean,
        val canGoNext: Boolean, val duration: Long, val position: Long,
        val speed: Float, val measuredAt: Long
    ) {
        fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
            val elapsed = if (playing && speed.isFinite() && speed > 0) ((now - measuredAt).coerceAtLeast(0) * speed).toLong() else 0L
            return (position + elapsed).coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
        }
    }
    private var owner: Any? = null
    private var servicePlayer: java.lang.ref.WeakReference<Player>? = null
    fun capture(player: Player): Preview? {
        val item = player.currentMediaItem ?: return null
        val metadata = player.mediaMetadata
        val shared = SharedAudio.isShared(item)
        return Preview(item.mediaId, metadata.title?.toString(), metadata.artist?.toString(), shared,
            if (shared) metadata.artworkData else null, player.isPlaying, player.shuffleModeEnabled,
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && player.hasNextMediaItem(),
            player.duration.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.playbackParameters.speed,
            SystemClock.elapsedRealtime())
    }
    fun current(player: Player?): Preview? = (servicePlayer?.get() ?: player)?.let(::capture)
    internal class Publisher(player: Player) {
        init { owner = this; servicePlayer = java.lang.ref.WeakReference(player) }
        fun release() { if (owner === this) { owner = null; servicePlayer = null } }
    }
}
