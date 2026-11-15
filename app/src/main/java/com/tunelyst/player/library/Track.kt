package com.tunelyst.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val duration: Long, val folder: String = "", val volume: String = "", val trackNumber: Int = 0) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artwork: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    fun item(source: String? = null, suggested: Boolean = false, queueOrigin: String? = null): MediaItem {
        val extras = android.os.Bundle().apply {
            if (!source.isNullOrBlank()) putString("tunelyst.source", source)
            if (suggested) putBoolean("tunelyst.suggested", true)
            if (queueOrigin == QueuePriority.NEXT || queueOrigin == QueuePriority.QUEUED) putString(QueuePriority.KEY, queueOrigin)
        }
        return MediaItem.Builder().setMediaId(id.toString()).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setArtworkUri(artwork).setExtras(extras).build()).build()
    }
}

object Library {
    fun scan(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME, MediaStore.Audio.Media.TRACK)
        fun query(projection: Array<String>) = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")
        // A few providers reject RELATIVE_PATH. Keep scanning and group those tracks as unknown.
        val cursor = try { query(columns) } catch (_: IllegalArgumentException) {
            try { query(columns.dropLast(1).toTypedArray()) } catch (_: IllegalArgumentException) {
                try { query(columns.dropLast(2).toTypedArray()) } catch (_: IllegalArgumentException) { query(columns.dropLast(3).toTypedArray()) }
            }
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                fun text(index: Int, fallback: String): String = c.getString(index)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback
                tracks.add(Track(c.getLong(0), text(1, "Untitled"), text(2, "Unknown artist"), text(3, "Unknown album"), c.getLong(4), c.getLong(5), (if (c.columnCount > 6) c.getString(6).orEmpty().trim('/') else ""),
                    (if (c.columnCount > 7) c.getString(7).orEmpty().ifBlank { "unknown" } else "unknown"), if (c.columnCount > 8 && !c.isNull(8)) c.getInt(8) else 0))
            }
        }
        return tracks
    }
}
