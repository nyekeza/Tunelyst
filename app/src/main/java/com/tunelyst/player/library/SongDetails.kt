package com.tunelyst.player

import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

internal object SongDetails {
    fun showShared(activity: AppCompatActivity, metadata: androidx.media3.common.MediaMetadata, duration: Long) {
        val extras = metadata.extras
        fun text(key: String): String = extras?.getString(key)?.takeIf { it.isNotBlank() } ?: "Unavailable"
        val durationMs = duration.takeIf { it > 0 } ?: extras?.getLong(SharedAudioMetadata.DURATION, -1L)?.takeIf { it > 0 }
        val durationText = durationMs?.let { val s = it / 1000; if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) } ?: "Unavailable"
        val size = extras?.getLong(SharedAudioMetadata.SIZE, -1L)?.takeIf { it >= 0 }?.let { "$it bytes" } ?: "Unavailable"
        val bitrate = extras?.getLong(SharedAudioMetadata.BITRATE, -1L)?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "Unavailable"
        val info = listOf("Artist: ${metadata.artist ?: "Unavailable"}", "Album: ${metadata.albumTitle ?: "Unavailable"}", "Duration: $durationText",
            "File: ${text(SharedAudioMetadata.FILE)}", "Format: ${text(SharedAudioMetadata.MIME)}", "Size: $size", "Track: ${text(SharedAudioMetadata.TRACK)}",
            "Genre: ${text(SharedAudioMetadata.GENRE)}", "Year: ${text(SharedAudioMetadata.YEAR)}", "Bitrate: $bitrate",
            "Source: Shared audio (temporary access)")
        MaterialAlertDialogBuilder(activity).setTitle(metadata.title ?: "Shared audio").setMessage(info.joinToString("\n"))
            .setPositiveButton("Close", null).show()
    }
    fun show(activity: AppCompatActivity, io: Executor, track: Track) {
        io.execute {
            val extra = runCatching {
                activity.contentResolver.query(track.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) emptyList() else listOfNotNull(
                        c.getString(0)?.let { "File: $it" }, c.getString(1)?.let { "Format: $it" },
                        if (!c.isNull(2)) "Size: ${c.getLong(2)} bytes" else null)
                } ?: emptyList()
            }.getOrDefault(emptyList())
            val duration = track.duration.coerceAtLeast(0) / 1000
            val info = listOf("Artist: ${track.artist}", "Album: ${track.album}", "Duration: ${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                "Location: ${track.volume.ifBlank { "Unknown storage" }} / ${track.folder.ifBlank { "Unknown folder" }}") + extra
            activity.runOnUiThread { if (!activity.isDestroyed) MaterialAlertDialogBuilder(activity).setTitle(track.title)
                .setMessage(info.joinToString("\n")).setPositiveButton("Close", null).show() }
        }
    }
}
