package com.tunelyst.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.InputStream

internal data class DecodedArtwork(val bitmap: Bitmap, val albumWide: Boolean)

/** Different providers expose covers differently. Always try track-level fallbacks. */
internal object ArtworkDecoder {
    fun firstAvailable(album: () -> Bitmap?, thumbnail: () -> Bitmap?, embedded: () -> Bitmap?): DecodedArtwork? {
        try { album()?.let { return DecodedArtwork(it, true) } } catch (_: Exception) { }
        try { thumbnail()?.let { return DecodedArtwork(it, false) } } catch (_: Exception) { }
        try { embedded()?.let { return DecodedArtwork(it, false) } } catch (_: Exception) { }
        return null
    }
    fun decode(context: Context, track: Track, target: Int): DecodedArtwork? = firstAvailable(
        album = {
            if (track.albumId < 0) null else decodeStream(target) { context.contentResolver.openInputStream(track.artwork) }
        },
        thumbnail = { context.contentResolver.loadThumbnail(track.uri, Size(target, target), null) },
        embedded = {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                val bytes = retriever.embeddedPicture
                if (bytes == null) null else decodeStream(target) { ByteArrayInputStream(bytes) }
            } finally { retriever.release() }
        }
    )?.let { result ->
        val bitmap = result.bitmap
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= target || target <= 0) result else {
            val width = (bitmap.width.toLong() * target / largest).toInt().coerceAtLeast(1)
            val height = (bitmap.height.toLong() * target / largest).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) bitmap.recycle()
            DecodedArtwork(scaled, result.albumWide)
        }
    }
    internal fun decodeStream(target: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = open() ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample >= target * 2) sample *= 2
        return open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
