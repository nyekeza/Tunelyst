package com.tunelyst.player

import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/** Owns the temporary selection and batch menu; music files are deleted only by MediaBatchActions. */
internal class SongSelectionController(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val io: Executor
        val main: Handler
        val db: LibraryDb
        val batchActions: MediaBatchActions
        val scope: String
        val playlistId: Long?
        fun selectionChanged(active: Boolean, count: Int)
        fun render()
        fun toast(message: String)
        fun favoritesChanged()
        fun playlistChanged(id: Long)
        fun createPlaylist(after: (Long) -> Unit)
    }
    var active = false
        private set
    private var selectionScope: String? = null
    private val selected = linkedMapOf<Long, Track>()
    private var shownSongs: List<Track> = emptyList()
    val ids: Set<Long>? get() = if (active) selected.keys else null
    val count: Int get() = selected.size

    fun clear() {
        active = false; selectionScope = null; selected.clear(); shownSongs = emptyList()
        host.selectionChanged(false, 0)
    }
    fun clearIfScopeChanged() { if (active && selectionScope != host.scope) clear() }
    fun start(first: Track? = null) {
        active = true; selectionScope = host.scope; selected.clear()
        if (first != null) selected[first.id] = first
        host.render(); host.selectionChanged(true, count)
    }
    fun stop() { clear(); host.render() }
    fun setShownSongs(songs: List<Track>) {
        // Lists supplied by the page are stable snapshots. Deduplicate only if
        // the user actually asks to select everything, not on every render.
        shownSongs = songs
        host.selectionChanged(active, count)
    }
    fun toggle(track: Track) {
        if (!active) return
        if (selected.remove(track.id) == null) selected[track.id] = track
        host.selectionChanged(true, count)
    }
    fun showActions() {
        if (!active) return
        val choices = arrayOf("Select all shown", "Add to liked songs", "Add to playlist", "Share songs", "Delete from device")
        MaterialAlertDialogBuilder(host.activity).setTitle("${selected.size} selected").setItems(choices) { _, which ->
            if (which == 0) {
                val seen = HashSet<Long>()
                shownSongs.forEach { if (seen.add(it.id)) selected[it.id] = it }; host.selectionChanged(true, count)
            } else {
                val songs = selected.values.toList()
                if (songs.isEmpty()) { host.toast("Select at least one song"); return@setItems }
                when (which) {
                    1 -> addFavorites(songs)
                    2 -> choosePlaylist(songs)
                    3 -> host.batchActions.share(songs)
                    4 -> host.batchActions.confirmDelete(songs)
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun addFavorites(songs: List<Track>) {
        val ids = songs.map { it.id }
        host.io.execute {
            val result = runCatching { LibraryDb(host.activity.applicationContext).use { it.addFavorites(ids) } }
            host.main.post { if (!host.activity.isDestroyed) {
                result.onSuccess { count -> host.favoritesChanged(); host.toast("Added $count to liked songs"); clear(); host.render() }
                    .onFailure { host.toast("Couldn't add songs to liked songs") }
            } }
        }
    }
    private fun choosePlaylist(songs: List<Track>) {
        val lists = host.db.playlists(); val choices = listOf("＋ Create playlist") + lists.map { it.second }
        fun add(id: Long) {
            val ids = songs.map { it.id }
            host.io.execute {
                val result = runCatching { LibraryDb(host.activity.applicationContext).use { it.addManyToPlaylist(id, ids) } }
                host.main.post { if (!host.activity.isDestroyed) {
                    result.onSuccess { count -> host.toast("Added $count songs to playlist"); host.playlistChanged(id); clear(); host.render() }
                        .onFailure { host.toast("Couldn't add songs to playlist") }
                } }
            }
        }
        MaterialAlertDialogBuilder(host.activity).setTitle("Add songs to playlist").setItems(choices.toTypedArray()) { _, which ->
            if (which == 0) host.createPlaylist { id -> add(id) } else add(lists[which - 1].first)
        }.setNegativeButton("Cancel", null).show()
    }
}
