package com.tunelyst.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

/** Reads only sender-granted content. The file is not copied or imported. */
internal object SharedAudioMetadata {
    const val RESOLVED = "tunelyst.shared.resolved"
    const val FILE = "tunelyst.shared.file"
    const val SIZE = "tunelyst.shared.size"
    const val MIME = "tunelyst.shared.mime"
    const val DURATION = "tunelyst.shared.duration"
    const val GENRE = "tunelyst.shared.genre"
    const val YEAR = "tunelyst.shared.year"
    const val BITRATE = "tunelyst.shared.bitrate"
    const val TRACK = "tunelyst.shared.track"
    private fun clean(value: String?): String? = value?.trim()?.take(300)?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
    fun read(context: Context, uri: Uri, old: MediaMetadata): MediaMetadata {
        val extras = Bundle(old.extras ?: Bundle.EMPTY).apply { putBoolean(RESOLVED, true) }
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = clean(c.getString(n))
                        if (s >= 0 && !c.isNull(s) && c.getLong(s) >= 0) extras.putLong(SIZE, c.getLong(s))
                    }
                }
            }
        } else if (uri.scheme == "file") name = clean(uri.lastPathSegment)
        name?.let { extras.putString(FILE, it) }
        runCatching { clean(context.contentResolver.getType(uri)) }.getOrNull()?.let { extras.putString(MIME, it) }
        var title: String? = null; var artist: String? = null; var album: String? = null; var art: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun get(key: Int) = runCatching { clean(retriever.extractMetadata(key)) }.getOrNull()
            title = get(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            get(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { extras.putString(GENRE, it) }
            get(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { extras.putString(YEAR, it) }
            get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { extras.putString(TRACK, it) }
            get(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let { extras.putLong(BITRATE, it) }
            get(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let { extras.putLong(DURATION, it) }
            if (!extras.containsKey(MIME)) get(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { extras.putString(MIME, it) }
            art = runCatching { retriever.embeddedPicture?.let(::boundedArtwork) }.getOrNull()
        } catch (_: Exception) { /* Optional metadata may not exist or permission may have expired. */ }
        finally { runCatching { retriever.release() } }
        val builder = old.buildUpon().setTitle(title ?: name ?: clean(old.title?.toString()) ?: "Shared audio")
            .setArtist(artist ?: "Unknown artist").setAlbumTitle(album).setExtras(extras)
        if (art != null) builder.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return builder.build()
    }
    /** Bound the bitmap sent through the media session; never send the original image. */
    private fun boundedArtwork(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            for (quality in listOf(85, 65, 45, 25)) {
                val output = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= 64 * 1024) return output.toByteArray()
            }
            return null
        } finally { bitmap.recycle() }
    }
}

/** Enrich the active shared item after playback starts; stale results never replace a newer queue. */
internal class SharedAudioMetadataController(
    private val context: Context,
    private val player: Player,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val read: (Context, Uri, MediaMetadata) -> MediaMetadata = SharedAudioMetadata::read
) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var closed = false
    init { player.addListener(this); request(player.currentMediaItem) }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { request(mediaItem) }
    private fun request(item: MediaItem?) {
        val token = ++generation
        val selected = item ?: return
        if (closed || !SharedAudio.isShared(selected) || selected.mediaMetadata.extras?.getBoolean(SharedAudioMetadata.RESOLVED) == true) return
        val uri = selected.localConfiguration?.uri ?: return
        val id = selected.mediaId
        worker.execute {
            val metadata = runCatching { read(context.applicationContext, uri, selected.mediaMetadata) }.getOrNull() ?: return@execute
            main.post {
                val current = player.currentMediaItem
                if (!closed && token == generation && current?.mediaId == id && current.localConfiguration?.uri == uri && SharedAudio.isShared(current)) {
                    player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(metadata).build())
                }
            }
        }
    }
    fun release() { closed = true; generation++; player.removeListener(this); worker.shutdown() }
}
