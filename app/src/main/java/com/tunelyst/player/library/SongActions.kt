package com.tunelyst.player

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/** Song options, playlist dialogs and explicit additions to the playback queue. */
internal class SongActions(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val io: Executor
        val db: LibraryDb
        val favorites: Set<Long>
        val player: MediaController?
        val playlistId: Long?
        val canSelect: Boolean
        val batchActions: MediaBatchActions
        fun toggleFavorite(track: Track)
        fun select(track: Track)
        fun updatePlaylist(ids: List<Long>)
        fun render()
        fun toast(message: String)
    }
    fun menu(t: Track): Unit = with(host) {
        val options = mutableListOf(if (t.id in favorites) "Remove from liked songs" else "Like song", "Play next", "Add to queue", "Add to playlist", "Song details", "Share song", "Delete from device")
        if (canSelect) options.add("Select songs")
        if (playlistId != null) { options.add("Move up in playlist"); options.add("Move down in playlist"); options.add("Remove from this playlist") }
        MaterialAlertDialogBuilder(activity).setTitle(t.title).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Remove from liked songs", "Like song" -> toggleFavorite(t)
                "Play next" -> addToQueue(t, true)
                "Add to queue" -> addToQueue(t, false)
                "Add to playlist" -> choosePlaylist(t)
                "Song details" -> SongDetails.show(activity, io, t)
                "Share song" -> batchActions.share(listOf(t))
                "Delete from device" -> batchActions.confirmDelete(listOf(t))
                "Select songs" -> select(t)
                "Move up in playlist", "Move down in playlist" -> playlistId?.let { id ->
                    val delta = if (options[which] == "Move up in playlist") -1 else 1
                    if (db.movePlaylistTrack(id, t.id, delta)) { updatePlaylist(db.trackIds(id)); render() } else toast("Can't move further")
                }
                "Remove from this playlist" -> playlistId?.let { db.removeFromPlaylist(it, t.id); updatePlaylist(db.trackIds(it)); render() }
            }
        }.show(); Unit
    }
    fun addToQueue(track: Track, playNext: Boolean): Unit = with(host) {
        val p = player ?: run { toast("Player connecting…"); return }
        if (p.currentMediaItem == null) { p.addMediaItem(track.item()); p.prepare(); toast("Added to queue"); return }
        val args = Bundle().apply {
            putString(PlaybackService.SMART_SHUFFLE_MODE, if (playNext) "next" else "append")
            putLong("id", track.id); putString("title", track.title); putString("artist", track.artist); putString("album", track.album)
            putLong("albumId", track.albumId); putLong("duration", track.duration)
        }
        val future = p.sendCustomCommand(SessionCommand(PlaybackService.SMART_SHUFFLE_COMMAND, Bundle.EMPTY), args)
        future.addListener({ if (!activity.isDestroyed) {
            val ok = runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            toast(if (!ok) "Couldn't change queue" else if (playNext) "Added to play next" else "Added to queue")
        } }, activity.mainExecutor)
    }
    fun createPlaylist(after: ((Long) -> Unit)? = null) = with(host) {
        val input = EditText(activity).apply { hint = "Playlist name"; setSingleLine() }
        MaterialAlertDialogBuilder(activity).setTitle("New playlist").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) toast("Name cannot be empty") else { val id = db.createPlaylist(name); if (id < 0) toast("Couldn't create playlist") else { after?.invoke(id); render() } }
        }.show(); Unit
    }
    fun choosePlaylist(t: Track) = with(host) {
        val lists = db.playlists(); val choices = listOf("＋ Create playlist") + lists.map { it.second }
        MaterialAlertDialogBuilder(activity).setTitle("Add to playlist").setItems(choices.toTypedArray()) { _, which ->
            if (which == 0) createPlaylist { id -> addToPlaylist(id, t) } else addToPlaylist(lists[which - 1].first, t)
        }.show(); Unit
    }
    fun addToPlaylist(id: Long, t: Track) = host.toast(if (host.db.addToPlaylist(id, t.id)) "Added to playlist" else "Already in playlist")
    fun confirmDelete(id: Long, name: String) = with(host) {
        MaterialAlertDialogBuilder(activity).setTitle("Delete playlist?").setMessage("Delete $name? Your music files won't be affected.").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> db.deletePlaylist(id); render() }.show(); Unit
    }
}
