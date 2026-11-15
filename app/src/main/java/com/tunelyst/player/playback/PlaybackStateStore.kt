package com.tunelyst.player

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class SavedQueue(val ids: List<Long>, val index: Int, val positionMs: Long, val repeatMode: Int, val shuffleOrder: List<Int>? = null, val signatures: List<String>? = null, val sources: List<String?>? = null, val suggestions: List<Boolean>? = null, val queueOrigins: List<String?>? = null)

/** Local queue snapshot. Audio files are never copied. Restored playback starts paused. */
internal class PlaybackStateStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "nyimbo-queue.json"))
    @Synchronized fun clear() { file.delete() }
    @Synchronized fun save(state: SavedQueue) {
        val json = JSONObject().put("version", 1).put("ids", JSONArray(state.ids))
            .put("index", state.index).put("position", state.positionMs.coerceAtLeast(0)).put("repeat", state.repeatMode)
        state.shuffleOrder?.let { json.put("shuffleOrder", JSONArray(it)) }
        state.signatures?.let { json.put("signatures", JSONArray(it)) }
        state.sources?.let { sources -> json.put("sources", JSONArray().apply { sources.forEach { put(it ?: JSONObject.NULL) } }) }
        state.suggestions?.let { json.put("suggestions", JSONArray(it)) }
        state.queueOrigins?.let { values -> json.put("queueOrigins", JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }) }
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun load(): SavedQueue? = try {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(json.optInt("version") == 1)
        val array = json.getJSONArray("ids")
        require(array.length() <= 100_000)
        val ids = (0 until array.length()).map { array.getLong(it) }
        val order = json.optJSONArray("shuffleOrder")?.let { a ->
            if (a.length() != ids.size) null else (0 until a.length()).map { a.getInt(it) }.takeIf { it.toSet() == ids.indices.toSet() }
        }
        val signatures = json.optJSONArray("signatures")?.let { a ->
            if (a.length() != ids.size) null else (0 until a.length()).map { a.getString(it) }
        }
        val sources = json.optJSONArray("sources")?.let { a ->
            if (a.length() != ids.size) null else (0 until a.length()).map { if (a.isNull(it)) null else a.optString(it).take(120) }
        }
        val suggestions = json.optJSONArray("suggestions")?.let { a ->
            if (a.length() != ids.size) null else (0 until a.length()).map { a.optBoolean(it, false) }
        }
        val origins = json.optJSONArray("queueOrigins")?.let { a ->
            if (a.length() != ids.size) null else (0 until a.length()).map { i ->
                if (a.isNull(i)) null else a.optString(i).takeIf { it == QueuePriority.NEXT || it == QueuePriority.QUEUED }
            }
        }
        if (ids.isEmpty()) null else SavedQueue(ids, json.optInt("index", 0).coerceIn(0, ids.lastIndex),
            json.optLong("position", 0).coerceAtLeast(0), json.optInt("repeat", 0).coerceIn(0, 2), order, signatures, sources, suggestions, origins)
    } catch (_: Exception) { null }

    companion object {
        fun signature(title: String, artist: String, album: String) = listOf(title, artist, album).joinToString("\u0000") { it.trim().lowercase() }
        fun signature(t: Track) = signature(t.title, t.artist, t.album)
    }
    private fun surviving(state: SavedQueue, library: List<Track>): List<Pair<Int, Track>> {
        val byId = library.associateBy { it.id }
        return state.ids.mapIndexedNotNull { index, id ->
            val track = byId[id] ?: return@mapIndexedNotNull null
            if (state.signatures != null && state.signatures.getOrNull(index) != signature(track)) null else index to track
        }
    }
    internal data class ResolvedQueue(
        val songs: List<Track>, val index: Int, val shuffleOrder: List<Int>?,
        val metadata: List<Pair<String?, Boolean>>, val queueOrigins: List<String?>
    )
    /** Build the library lookup and surviving queue only once, even for large snapshots. */
    fun resolveQueue(state: SavedQueue, library: List<Track>): ResolvedQueue? {
        val found = surviving(state, library)
        if (found.isEmpty()) return null
        val current = found.indexOfFirst { it.first >= state.index }.let { if (it < 0) found.lastIndex else it }
        val order = state.shuffleOrder?.takeIf { it.size == state.ids.size && it.toSet() == state.ids.indices.toSet() }?.let { oldOrder ->
            val remap = found.mapIndexed { index, item -> item.first to index }.toMap()
            oldOrder.mapNotNull { remap[it] }
        }
        return ResolvedQueue(found.map { it.second }, current, order,
            found.map { (index, _) -> state.sources?.getOrNull(index) to (state.suggestions?.getOrNull(index) ?: false) },
            found.map { (index, _) -> state.queueOrigins?.getOrNull(index) })
    }
    fun resolveMetadata(state: SavedQueue, library: List<Track>): List<Pair<String?, Boolean>> =
        surviving(state, library).map { (index, _) -> state.sources?.getOrNull(index) to (state.suggestions?.getOrNull(index) ?: false) }
    fun resolveQueueOrigins(state: SavedQueue, library: List<Track>): List<String?> =
        surviving(state, library).map { (index, _) -> state.queueOrigins?.getOrNull(index) }
    fun resolveShuffleOrder(state: SavedQueue, library: List<Track>): List<Int>? {
        val order = state.shuffleOrder ?: return null
        if (order.size != state.ids.size || order.toSet() != state.ids.indices.toSet()) return null
        val surviving = surviving(state, library).map { it.first }
        val remap = surviving.withIndex().associate { it.value to it.index }
        return order.mapNotNull { remap[it] }
    }
    /** Missing songs are skipped; the stored current song is preferred if present. */
    fun resolve(state: SavedQueue, library: List<Track>): Pair<List<Track>, Int>? {
        val found = surviving(state, library)
        if (found.isEmpty()) return null
        val index = found.indexOfFirst { it.first >= state.index }.let { if (it < 0) found.lastIndex else it }
        return found.map { it.second } to index
    }
}
