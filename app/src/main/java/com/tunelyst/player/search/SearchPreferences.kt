package com.tunelyst.player

import android.content.Context
import org.json.JSONArray
import java.util.Locale

internal class SearchPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("search_preferences", Context.MODE_PRIVATE)
    val sorts = listOf("Relevance", "Title", "Artist", "Album", "Duration")
    fun sort() = prefs.getString("sort", "Relevance")?.takeIf { it in sorts } ?: "Relevance"
    fun setSort(value: String) { if (value in sorts) prefs.edit().putString("sort", value).apply() }
    fun sorted(tracks: List<Track>) = when (sort()) {
        "Title" -> tracks.sortedBy { it.title.lowercase(Locale.ROOT) }
        "Artist" -> tracks.sortedBy { it.artist.lowercase(Locale.ROOT) }
        "Album" -> tracks.sortedBy { it.album.lowercase(Locale.ROOT) }
        "Duration" -> tracks.sortedBy { it.duration }
        else -> tracks
    }
    fun recent(): List<String> = runCatching {
        val json = JSONArray(prefs.getString("recent_queries", "[]"))
        (0 until json.length()).map { json.optString(it) }.filter { it.isNotBlank() }.take(12)
    }.getOrDefault(emptyList())
    fun remember(query: String) {
        val q = query.trim().take(200); if (q.isEmpty()) return
        val list = (listOf(q) + recent().filterNot { it.equals(q, true) }).take(12)
        prefs.edit().putString("recent_queries", JSONArray(list).toString()).apply()
    }
    fun clear() { prefs.edit().remove("recent_queries").apply() }
}
