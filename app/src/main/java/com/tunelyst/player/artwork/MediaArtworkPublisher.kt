package com.tunelyst.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Supplies current artwork bytes to the media session when the provider supports it. */
internal class MediaArtworkPublisher(context: Context, private val player: ExoPlayer) : Player.Listener {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var requested: String? = null
    private var closed = false
    private var generation = 0
    private var retryCount = 0
    private var retryId: String? = null
    private val retry = Runnable { if (!closed) { requested = null; update(force = true) } }
    init { player.addListener(this); update() }
    override fun onEvents(player: Player, events: Player.Events) { update() }
    fun refresh() { if (!closed) { main.removeCallbacks(retry); requested = null; retryCount = 0; update(force = true) } }
    private fun update(force: Boolean = false) {
        val item = player.currentMediaItem ?: run { requested = null; main.removeCallbacks(retry); return }
        if (retryId != item.mediaId) { retryId = item.mediaId; retryCount = 0 }
        if ((!force && item.mediaMetadata.artworkData != null) || requested == item.mediaId) return
        main.removeCallbacks(retry)
        val id = item.mediaId.toLongOrNull() ?: return
        val albumId = runCatching { android.content.ContentUris.parseId(item.mediaMetadata.artworkUri!!) }.getOrDefault(-1L)
        val meta = item.mediaMetadata
        val track = Track(id, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), meta.albumTitle?.toString().orEmpty(), albumId, 0)
        requested = item.mediaId
        val request = ++generation
        worker.execute {
            val bytes = runCatching {
                val decoded = ArtworkDecoder.decode(app, track, 384)?.bitmap ?: return@runCatching null
                try { ByteArrayOutputStream().use { out -> decoded.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() } }
                finally { decoded.recycle() }
            }.getOrNull()
            main.post {
                if (!closed && generation == request && bytes == null && player.currentMediaItem?.mediaId == item.mediaId && requested == item.mediaId) {
                    val delay = (30_000L shl retryCount.coerceAtMost(4)).coerceAtMost(300_000L)
                    retryCount++; main.removeCallbacks(retry); main.postDelayed(retry, delay)
                }
                if (!closed && generation == request && bytes != null && player.currentMediaItem?.mediaId == item.mediaId) {
                    retryCount = 0
                    val current = player.currentMediaItem ?: return@post
                    val metadata = current.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
                    player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(metadata).build())
                }
            }
        }
    }
    fun release() { closed = true; generation++; main.removeCallbacks(retry); player.removeListener(this); worker.shutdownNow() }
}
