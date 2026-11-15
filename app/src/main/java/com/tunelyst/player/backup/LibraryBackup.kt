package com.tunelyst.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/** Portable local data only: no music files, settings or credentials. */
internal object LibraryBackup {
    private const val LIMIT = 10 * 1024 * 1024
    data class ImportResult(val favorites: Set<Long>, val stats: Map<Long, TrackStats>, val playlists: List<Pair<String, List<Long>>>, val skipped: Int)
    private fun ref(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album).put("duration", t.duration)
    private fun signature(title: String, artist: String, album: String, duration: Long) =
        listOf(title, artist, album).joinToString("\u0000") { it.trim().lowercase() } + "\u0000" + duration
    private fun key(t: Track) = signature(t.title, t.artist, t.album, t.duration)
    fun write(db: LibraryDb, tracks: List<Track>, output: OutputStream): Int {
        val byId = tracks.associateBy { it.id }
        var omitted = 0
        omitted += db.favorites().count { it !in byId }
        omitted += db.listeningStats().keys.count { it !in byId }
        db.playlists().forEach { (id, _) -> omitted += db.trackIds(id).count { it !in byId } }
        val likes = JSONArray(); db.favorites().forEach { byId[it]?.let { t -> likes.put(ref(t)) } }
        val stats = JSONArray(); db.listeningStats().forEach { (id, st) -> byId[id]?.let { t ->
            stats.put(JSONObject().put("song", ref(t)).put("plays", st.plays).put("skips", st.skips).put("last", st.lastPlayedMs))
        } }
        val playlists = JSONArray(); db.playlists().forEach { (id, name) ->
            val songs = JSONArray(); db.trackIds(id).forEach { byId[it]?.let { t -> songs.put(ref(t)) } }
            playlists.put(JSONObject().put("name", name).put("songs", songs))
        }
        val doc = JSONObject().put("format", "nyimbo-library").put("version", 1).put("favorites", likes).put("stats", stats).put("playlists", playlists)
        output.bufferedWriter(Charsets.UTF_8).use { it.write(doc.toString()) }
        return omitted
    }
    fun read(input: InputStream, tracks: List<Track>): ImportResult {
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= LIMIT) { "Backup is too large" }; out.write(buffer, 0, n) }
            out.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getString("format") == "nyimbo-library" && json.getInt("version") == 1) { "Unsupported backup" }
        val byId = tracks.associateBy { it.id }; val byKey = tracks.groupBy(::key)
        var skipped = 0
        fun resolve(obj: JSONObject): Long? {
            val sig = signature(obj.getString("title"), obj.getString("artist"), obj.getString("album"), obj.getLong("duration"))
            val id = obj.getLong("id")
            val song = byId[id]?.takeIf { key(it) == sig } ?: byKey[sig]?.singleOrNull()
            if (song == null) skipped++
            return song?.id
        }
        fun boundedArray(o: JSONObject, name: String): JSONArray = o.getJSONArray(name).also { require(it.length() <= 100_000) }
        val fav = boundedArray(json, "favorites"); val favorites = (0 until fav.length()).mapNotNull { resolve(fav.getJSONObject(it)) }.toSet()
        val statArray = boundedArray(json, "stats"); val stats = mutableMapOf<Long, TrackStats>()
        for (i in 0 until statArray.length()) {
            val item = statArray.getJSONObject(i); val id = resolve(item.getJSONObject("song"))
            val plays = item.getInt("plays"); val skips = item.getInt("skips"); val last = item.getLong("last")
            require(plays >= 0 && skips >= 0 && last >= 0)
            if (id != null) stats[id] = TrackStats(plays, skips, last)
        }
        val lists = boundedArray(json, "playlists"); require(lists.length() <= 10_000)
        val playlists = (0 until lists.length()).map { i ->
            val item = lists.getJSONObject(i); val name = item.getString("name").trim().take(120); require(name.isNotEmpty())
            val songs = boundedArray(item, "songs")
            name to (0 until songs.length()).mapNotNull { resolve(songs.getJSONObject(it)) }.distinct()
        }
        return ImportResult(favorites, stats, playlists, skipped)
    }
}
