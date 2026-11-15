package com.tunelyst.player

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Builds album, artist, folder and playlist contents without owning navigation state. */
internal class CollectionPage(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val content: LinearLayout
        val tracks: List<Track>
        val groupIds: List<Long>
        val groupTitle: String?
        val screen: String
        val playlistId: Long?
        var playlistFilter: String
        var playlistSort: String
        val db: LibraryDb
        val surface: Int
        val onSurface: Int
        val muted: Int
        fun dp(value: Int): Int
        fun row(): LinearLayout
        fun button(text: String, action: () -> Unit): Button
        fun label(text: String, size: Float, color: Int): TextView
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun matches(track: Track): Boolean
        fun addHero(title: String, tracks: List<Track>)
        fun editor(id: Long): PlaylistEditor
        fun addTracks(tracks: List<Track>)
        fun stateCard(title: String, detail: String)
        fun render()
    }
    fun render() = with(host) {
        val ids = groupIds; val byId = tracks.associateBy { it.id }; val missing = ids.count { it !in byId }
        val unfiltered = ids.mapNotNull { byId[it] }.let { list -> if (screen == "Search") list else list.filter { matches(it) } }
        val filtered = if (playlistId != null && playlistFilter.isNotBlank()) unfiltered.filter { it.title.contains(playlistFilter, true) || it.artist.contains(playlistFilter, true) || it.album.contains(playlistFilter, true) } else unfiltered
        val list = when (if (playlistId != null) playlistSort else "Playlist order") {
            "Title" -> filtered.sortedBy { it.title.lowercase() }
            "Artist" -> filtered.sortedBy { it.artist.lowercase() }
            "Album" -> filtered.sortedBy { it.album.lowercase() }
            "Duration" -> filtered.sortedBy { it.duration }
            else -> filtered
        }
        addHero(groupTitle ?: "Collection", list)
        playlistId?.let { id ->
            val tools = row()
            tools.addView(button("Add songs") { editor(id).addSongs(id, tracks) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            tools.addView(button("Edit") { editor(id).edit(id, db.trackIds(id), tracks) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            val playlistOptions = iconButton(R.drawable.ny_more, "Playlist options", surface, onSurface) { }
            playlistOptions.setOnClickListener {
                PopupMenu(activity, playlistOptions).apply {
                    menu.add("Rename playlist").setOnMenuItemClickListener { editor(id).rename(id, groupTitle ?: "Playlist"); true }
                    show()
                }
            }
            tools.addView(playlistOptions, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(tools)
            val filters = row()
            filters.addView(button(if (playlistFilter.isBlank()) "Find in playlist" else "Find: $playlistFilter") {
                val input = EditText(activity).apply { hint = "Song, artist or album"; setSingleLine(); setText(playlistFilter) }
                MaterialAlertDialogBuilder(activity).setTitle("Find in playlist").setView(input).setNegativeButton("Cancel", null)
                    .setNeutralButton("Clear") { _, _ -> playlistFilter = ""; host.render() }
                    .setPositiveButton("Find") { _, _ -> playlistFilter = input.text.toString().trim(); host.render() }.show()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            filters.addView(button("Sort: $playlistSort") {
                val choices = arrayOf("Playlist order", "Title", "Artist", "Album", "Duration")
                MaterialAlertDialogBuilder(activity).setTitle("Display order").setSingleChoiceItems(choices, choices.indexOf(playlistSort)) { dialog, which -> playlistSort = choices[which]; dialog.dismiss(); host.render() }.setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(filters)
            if (missing > 0) content.addView(label("$missing unavailable ${if (missing == 1) "song" else "songs"} retained in this playlist", 12f, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        }
        if (playlistId != null && playlistFilter.isNotBlank() && list.isEmpty()) stateCard("No matches in this playlist", "Clear or change the playlist search.") else addTracks(list)
    }
}
