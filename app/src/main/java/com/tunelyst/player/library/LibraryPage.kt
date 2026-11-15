package com.tunelyst.player

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Renders the Library tabs; navigation and selection remain owned by the activity. */
internal class LibraryPage(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val content: LinearLayout
        val tab: String
        val index: LibraryIndex
        val tracks: List<Track>
        val favorites: Set<Long>
        val db: LibraryDb
        val librarySort: String
        val onSurface: Int
        val muted: Int
        val surface: Int
        fun dp(value: Int): Int
        fun row(): LinearLayout
        fun col(): LinearLayout
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun button(text: String, action: () -> Unit): Button
        fun pressable(color: Int, radius: Int): Drawable
        fun collage(list: List<Track>, size: Int): View
        fun artworkShape(key: String): Drawable
        fun setSort(sort: String)
        fun jumpToTitle(letter: String)
        fun render()
        fun addTracks(tracks: List<Track>)
        fun setRows(rows: List<LibraryListAdapter.Row>)
        fun openGroup(name: String, tracks: List<Track>)
        fun openPlaylist(id: Long, name: String)
        fun createPlaylist()
        fun confirmDeletePlaylist(id: Long, name: String)
        fun empty()
        fun stateCard(title: String, detail: String)
    }
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }
    fun render() = with(host) {
        when (tab) {
            "Songs" -> {
                val list = index.sorted(librarySort)
                val controls = row().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
                controls.addView(button("Sort: $librarySort") {
                    val choices = arrayOf("Title", "Artist", "Album", "Duration")
                    MaterialAlertDialogBuilder(activity).setTitle("Sort songs").setSingleChoiceItems(choices, choices.indexOf(librarySort)) { dialog, which ->
                        setSort(choices[which]); dialog.dismiss(); host.render()
                    }.setNegativeButton("Cancel", null).show()
                }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(4) })
                controls.addView(button("A–Z") {
                    val choices = (listOf("#") + ('A'..'Z').map { it.toString() }).toTypedArray()
                    MaterialAlertDialogBuilder(activity).setTitle("Jump to song title").setItems(choices) { _, which -> jumpToTitle(choices[which]) }
                        .setNegativeButton("Cancel", null).show()
                }, LinearLayout.LayoutParams(dp(64), dp(48)))
                content.addView(controls); addTracks(list)
            }
            "Albums" -> {
                val rows = index.albums.map { list ->
                    LibraryListAdapter.Row.Collection(list.first().album, "${list.first().artist} · ${list.size} tracks", list.first(), { openGroup(list.first().album, list) })
                }
                if (rows.isEmpty()) empty() else setRows(rows)
            }
            "Artists" -> {
                val rows = index.artists.map { list ->
                    val name = list.first().artist
                    LibraryListAdapter.Row.Collection(name, "${list.size} tracks", list.first(), { openGroup(name, list) })
                }
                if (rows.isEmpty()) empty() else setRows(rows)
            }
            "Folders" -> {
                val folders = index.folders
                fun folderName(list: List<Track>) = list.firstOrNull()?.folder.orEmpty().trim('/').substringAfterLast('/').ifBlank { "Unknown folder" }
                val repeated = folders.groupingBy { folderName(it.second).lowercase() }.eachCount()
                val rows = folders.map { (path, list) ->
                    val name = folderName(list); val storage = path.substringBefore(" · ", "")
                    val parent = list.firstOrNull()?.folder.orEmpty().trim('/').substringBeforeLast('/', "").substringAfterLast('/')
                    val hint = if ((repeated[name.lowercase()] ?: 0) > 1) listOf(storage, parent).filter { it.isNotBlank() }.joinToString(" · ") else ""
                    val detail = listOf("${list.size} tracks", hint).filter { it.isNotEmpty() }.joinToString(" · ")
                    LibraryListAdapter.Row.Collection(name, detail, list.firstOrNull(), { openGroup(name, list) })
                }
                if (rows.isEmpty()) empty() else setRows(rows)
            }
            else -> {
                val liked = tracks.filter { it.id in favorites }
                val heading = row().apply { setPadding(0, dp(6), 0, dp(10)) }
                val headerText = col(); headerText.addView(label("Your playlists", 21f, onSurface, true))
                headerText.addView(label("Made for your music", 12f, muted)); heading.addWeighted(headerText)
                heading.addView(button("＋ Create") { createPlaylist() }, LinearLayout.LayoutParams(-2, dp(48)))
                content.addView(heading)
                val likedCard = row().apply { minimumHeight = dp(78); setPadding(dp(10), dp(10), dp(12), dp(10)); background = pressable(surface, 16) }
                val likedArt = if (liked.isNotEmpty()) collage(liked, dp(56)) else label("♥", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = artworkShape("liked") }
                likedCard.addView(likedArt, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
                val likedText = col(); likedText.addView(label("Liked songs", 16f, onSurface, true)); likedText.addView(label("${liked.size} saved tracks", 12f, muted)); likedCard.addWeighted(likedText)
                likedCard.addView(label("›", 24f, muted)); likedCard.contentDescription = "Liked songs, ${liked.size} saved tracks"
                likedCard.isClickable = true; likedCard.isFocusable = true; likedCard.setOnClickListener { openGroup("Liked songs", liked) }
                content.addView(likedCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                val previews = db.playlistPreviews(); val byId = tracks.associateBy { it.id }
                val rows = db.playlists().map { (id, name) ->
                    val preview = previews[id]; val art = preview?.second.orEmpty().mapNotNull { byId[it] }
                    LibraryListAdapter.Row.Collection(name, "Playlist · ${preview?.first ?: 0} songs", null,
                        { openPlaylist(id, name) }, { confirmDeletePlaylist(id, name) }, art)
                }
                if (rows.isEmpty()) stateCard("No playlists yet", "Create a playlist to keep your favorite songs together.") else setRows(rows)
            }
        }
    }
}
