package com.tunelyst.player

import android.content.Context
import org.json.JSONArray

/** Retains the existing UI preferences and Search selection history on this device. */
internal class LibraryUiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("nyimbo_ui", Context.MODE_PRIVATE)

    fun librarySort(): String = preferences.getString("library_sort", "Title") ?: "Title"
    fun setLibrarySort(value: String) { preferences.edit().putString("library_sort", value).apply() }

    fun searchedSongIds(): List<Long> {
        val raw = preferences.getString("recent_song_ids", "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).map { json.optLong(it, -1L) }.filter { it >= 0 }.distinct().take(30)
        } catch (_: Exception) { emptyList() }
    }
    fun saveSearchedSong(id: Long) {
        val ids = (listOf(id) + searchedSongIds().filterNot { it == id }).take(30)
        preferences.edit().putString("recent_song_ids", JSONArray(ids).toString()).apply()
    }
    fun clearSearchedSongs() { preferences.edit().remove("recent_song_ids").apply() }
}
