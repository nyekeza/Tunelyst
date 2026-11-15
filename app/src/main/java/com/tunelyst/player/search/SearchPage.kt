package com.tunelyst.player

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal class SearchPage(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val content: LinearLayout
        val query: String
        val category: String
        val index: LibraryIndex
        val coordinator: SearchCoordinator
        val preferences: SearchPreferences
        val muted: Int
        fun label(text: String, size: Float, color: Int): TextView
        fun button(text: String, action: () -> Unit): Button
        fun stateCard(title: String, detail: String)
        fun empty()
        fun rows(rows: List<LibraryListAdapter.Row>, songs: List<Track>)
        fun openGroup(name: String, group: List<Track>)
        fun setQuery(value: String)
        fun recentlySearchedSongs()
        fun render()
    }
    fun render() = with(host) {
        if (query.isNotBlank()) {
            if (coordinator.searchedFor != query.trim()) content.addView(label("Searching…", 13f, muted))
            else {
                val songs = preferences.sorted(coordinator.results)
                if (category == "All" || category == "Songs") content.addView(button("Sort songs: ${preferences.sort()}") {
                    val sorts = preferences.sorts
                    MaterialAlertDialogBuilder(activity).setTitle("Sort song results").setSingleChoiceItems(sorts.toTypedArray(), sorts.indexOf(preferences.sort())) { dialog, i ->
                        preferences.setSort(sorts[i]); dialog.dismiss(); host.render()
                    }.setNegativeButton("Cancel", null).show()
                })
                val rows = SearchResultsLayout.rowsForCategory(index, songs, query, category) { name, group -> preferences.remember(query); openGroup(name, group) }
                if (rows.isEmpty()) {
                    if (category == "All") empty() else stateCard("No matching ${category.lowercase()}", "Try a different search or choose another category.")
                } else rows(rows, songs)
            }
        } else {
            val recent = preferences.recent()
            if (recent.isNotEmpty()) {
                content.addView(label("Recent searches", 18f, muted))
                recent.forEach { value -> content.addView(button(value) { setQuery(value); preferences.remember(value) }) }
                content.addView(button("Clear recent searches") { preferences.clear(); host.render() })
            }
            if (category == "All" || category == "Songs") recentlySearchedSongs()
            else stateCard("Search ${category.lowercase()}", "Enter a name in the search bar above.")
        }
    }
}
