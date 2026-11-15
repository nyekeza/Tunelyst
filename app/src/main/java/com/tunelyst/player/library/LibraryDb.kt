package com.tunelyst.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "nyimbo.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE favorites (track_id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playlist_tracks (playlist_id INTEGER NOT NULL, track_id INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id, track_id))")
        createStatsTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createStatsTable(db)
    }
    private fun createStatsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_stats (track_id INTEGER PRIMARY KEY, play_count INTEGER NOT NULL DEFAULT 0, skip_count INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0)")
    }
    fun recordPlay(id: Long, timestamp: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO listening_stats(track_id) VALUES(?)", arrayOf(id))
            db.execSQL("UPDATE listening_stats SET play_count = play_count + 1, last_played = ? WHERE track_id = ?", arrayOf(timestamp, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun recordSkip(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO listening_stats(track_id) VALUES(?)", arrayOf(id))
            db.execSQL("UPDATE listening_stats SET skip_count = skip_count + 1 WHERE track_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun listeningStats(): Map<Long, TrackStats> = readableDatabase.rawQuery(
        "SELECT track_id, play_count, skip_count, last_played FROM listening_stats", null
    ).use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), TrackStats(c.getInt(1), c.getInt(2), c.getLong(3))) }
    }
    fun clearListeningStats() { writableDatabase.delete("listening_stats", null, null) }
    fun favorites(): Set<Long> = readableDatabase.rawQuery("SELECT track_id FROM favorites", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }
    fun setFavorite(id: Long, favorite: Boolean) {
        if (favorite) writableDatabase.insertWithOnConflict("favorites", null, ContentValues().apply { put("track_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("favorites", "track_id=?", arrayOf(id.toString()))
    }
    fun addFavorites(ids: List<Long>): Int {
        val db = writableDatabase; var added = 0
        db.beginTransaction()
        try {
            ids.distinct().forEach { id ->
                if (db.insertWithOnConflict("favorites", null, ContentValues().apply { put("track_id", id) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }
    fun playlists(): List<Pair<Long, String>> = readableDatabase.rawQuery("SELECT id, name FROM playlists ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
    }
    /** Bounded artwork previews and song counts for all playlists, in one query. */
    fun playlistPreviews(): Map<Long, Pair<Int, List<Long>>> {
        val counts = mutableMapOf<Long, Int>()
        val covers = mutableMapOf<Long, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT playlist_id, track_id FROM playlist_tracks ORDER BY playlist_id, position", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                counts[id] = (counts[id] ?: 0) + 1
                val items = covers.getOrPut(id) { mutableListOf() }
                if (items.size < 4) items.add(c.getLong(1))
            }
        }
        return counts.mapValues { (id, count) -> count to covers[id].orEmpty().toList() }
    }
    fun createPlaylist(name: String): Long = writableDatabase.insert("playlists", null, ContentValues().apply { put("name", name) })
    /** Atomically create a playlist and snapshot distinct local queue IDs in order. */
    fun createPlaylistWithTracks(name: String, tracks: List<Long>): Long {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val id = database.insertOrThrow("playlists", null, ContentValues().apply { put("name", name) })
            tracks.distinct().forEachIndexed { position, track ->
                database.insertOrThrow("playlist_tracks", null, ContentValues().apply {
                    put("playlist_id", id); put("track_id", track); put("position", position)
                })
            }
            database.setTransactionSuccessful()
            return id
        } finally { database.endTransaction() }
    }
    fun deletePlaylist(id: Long) {
        writableDatabase.beginTransaction()
        try { writableDatabase.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString())); writableDatabase.delete("playlists", "id=?", arrayOf(id.toString())); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
    fun addToPlaylist(playlist: Long, track: Long): Boolean {
        val db = writableDatabase
        val next = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id=?", arrayOf(playlist.toString())).use { it.moveToFirst(); it.getInt(0) }
        return db.insertWithOnConflict("playlist_tracks", null, ContentValues().apply { put("playlist_id", playlist); put("track_id", track); put("position", next) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun removeFromPlaylist(playlist: Long, track: Long) { writableDatabase.delete("playlist_tracks", "playlist_id=? AND track_id=?", arrayOf(playlist.toString(), track.toString())) }
    fun trackIds(playlist: Long): List<Long> = readableDatabase.rawQuery("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position", arrayOf(playlist.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0)) }
    }
    fun renamePlaylist(id: Long, name: String): Boolean = writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString())) > 0
    fun addManyToPlaylist(playlist: Long, tracks: List<Long>): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            var next = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id=?", arrayOf(playlist.toString())).use { it.moveToFirst(); it.getInt(0) }
            tracks.distinct().forEach { id ->
                val result = db.insertWithOnConflict("playlist_tracks", null, ContentValues().apply { put("playlist_id", playlist); put("track_id", id); put("position", next) }, SQLiteDatabase.CONFLICT_IGNORE)
                if (result != -1L) { next++; added++ }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }
    fun movePlaylistTrack(playlist: Long, track: Long, delta: Int): Boolean {
        val db = writableDatabase
        var moved = false
        db.beginTransaction()
        try {
            val ids = trackIds(playlist).toMutableList()
            val from = ids.indexOf(track); val to = from + delta
            if (from >= 0 && to in ids.indices) {
                ids.removeAt(from); ids.add(to, track)
                ids.forEachIndexed { position, id ->
                    db.update("playlist_tracks", ContentValues().apply { put("position", position) }, "playlist_id=? AND track_id=?", arrayOf(playlist.toString(), id.toString()))
                }
                moved = true
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return moved
    }
    /** Apply an edit only if the playlist still matches the version displayed to the user. */
    fun replacePlaylistOrder(playlist: Long, expected: List<Long>, edited: List<Long>): Boolean {
        if (expected.distinct().size != expected.size || edited.distinct().size != edited.size || !expected.containsAll(edited)) return false
        val db = writableDatabase
        var ok = false
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM playlists WHERE id=?", arrayOf(playlist.toString())).use { it.moveToFirst() }
            if (exists && trackIds(playlist) == expected) {
                val retained = edited.toSet()
                expected.filter { it !in retained }.forEach { id -> db.delete("playlist_tracks", "playlist_id=? AND track_id=?", arrayOf(playlist.toString(), id.toString())) }
                edited.forEachIndexed { index, id ->
                    db.update("playlist_tracks", ContentValues().apply { put("position", index) }, "playlist_id=? AND track_id=?", arrayOf(playlist.toString(), id.toString()))
                }
                ok = true
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ok
    }
    /** Merge, never replace, existing user data. One transaction prevents partial imports. */
    fun mergeBackup(favorites: Set<Long>, stats: Map<Long, TrackStats>, playlists: List<Pair<String, List<Long>>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            favorites.forEach { id -> db.insertWithOnConflict("favorites", null, ContentValues().apply { put("track_id", id) }, SQLiteDatabase.CONFLICT_IGNORE) }
            stats.forEach { (id, stat) ->
                db.execSQL("INSERT OR IGNORE INTO listening_stats(track_id) VALUES(?)", arrayOf(id))
                db.execSQL("UPDATE listening_stats SET play_count=MAX(play_count,?), skip_count=MAX(skip_count,?), last_played=MAX(last_played,?) WHERE track_id=?", arrayOf<Any>(stat.plays, stat.skips, stat.lastPlayedMs, id))
            }
            playlists.forEach { (name, ids) ->
                val playlistId = db.insertOrThrow("playlists", null, ContentValues().apply { put("name", "$name (imported)") })
                ids.distinct().forEachIndexed { position, id ->
                    db.insertOrThrow("playlist_tracks", null, ContentValues().apply { put("playlist_id", playlistId); put("track_id", id); put("position", position) })
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

}
