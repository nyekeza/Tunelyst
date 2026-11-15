package com.tunelyst.player

import android.content.Context
import android.provider.MediaStore

/** Query just the saved queue when small. A null result asks the caller to use a full scan. */
internal object QueueRecoveryLookup {
    private const val CHUNK = 400
    private const val MAX_IDS = 2000
    fun find(context: Context, ids: List<Long>): List<Track>? {
        val unique = ids.distinct()
        if (unique.isEmpty()) return emptyList()
        if (unique.size > MAX_IDS || unique.any { it < 0 }) return null
        val expected = unique.toHashSet()
        val results = ArrayList<Track>(unique.size)
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.Audio.Media.TRACK)
        return try {
            unique.chunked(CHUNK).forEach { group ->
                val where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media._ID} IN (${List(group.size) { "?" }.joinToString(",")})"
                fun query(projection: Array<String>) = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, where, group.map { it.toString() }.toTypedArray(), null)
                val cursor = try { query(columns) } catch (_: IllegalArgumentException) {
                    try { query(columns.dropLast(1).toTypedArray()) } catch (_: IllegalArgumentException) {
                        try { query(columns.dropLast(2).toTypedArray()) } catch (_: IllegalArgumentException) { query(columns.dropLast(3).toTypedArray()) }
                    }
                } ?: return null
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id !in expected) continue // Some providers ignore selection arguments.
                        fun text(index: Int, fallback: String) = c.getString(index)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: fallback
                        results.add(Track(id, text(1, "Untitled"), text(2, "Unknown artist"), text(3, "Unknown album"), c.getLong(4), c.getLong(5),
                            if (c.columnCount > 6) c.getString(6).orEmpty().trim('/') else "",
                            if (c.columnCount > 7) c.getString(7).orEmpty().ifBlank { "unknown" } else "unknown",
                            if (c.columnCount > 8 && !c.isNull(8)) c.getInt(8) else 0))
                    }
                }
            }
            results
        } catch (_: Exception) { null }
    }
}
