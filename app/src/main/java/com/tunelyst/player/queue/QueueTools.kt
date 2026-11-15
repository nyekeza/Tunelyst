package com.tunelyst.player

import android.content.Context
import android.widget.EditText
import android.widget.PopupMenu
import android.view.View
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Queue actions operate on the displayed traversal and never remove the current item. */
internal object QueueTools {
    fun show(context: Context, anchor: View, player: MediaController) {
        PopupMenu(context, anchor).apply {
            menu.add("Clear upcoming songs").setOnMenuItemClickListener { clear(context, player); true }
            menu.add("Save queue as playlist").setOnMenuItemClickListener { save(context, player); true }
            show()
        }
    }
    internal data class Snapshot(val current: Int, val currentId: String?, val indices: List<Int>, val ids: List<String>)
    internal fun snapshot(player: Player): Snapshot {
        val indices = QueueTraversal.upcoming(player)
        return Snapshot(player.currentMediaItemIndex, player.currentMediaItem?.mediaId, indices, indices.map { player.getMediaItemAt(it).mediaId })
    }
    internal fun clearUpcoming(player: Player, expected: Snapshot): Boolean {
        if (snapshot(player) != expected || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        expected.indices.sortedDescending().forEach { player.removeMediaItem(it) }
        return true
    }
    private fun clear(context: Context, player: MediaController) {
        val expected = snapshot(player)
        if (expected.indices.isEmpty()) { android.widget.Toast.makeText(context, "No upcoming songs", android.widget.Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(context).setTitle("Clear upcoming songs?")
            .setMessage("Removes ${expected.indices.size} upcoming queue entries. The current song and music files stay.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                if (!clearUpcoming(player, expected)) android.widget.Toast.makeText(context, "Queue changed; try again", android.widget.Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun save(context: Context, player: MediaController) {
        val current = player.currentMediaItemIndex
        if (current !in 0 until player.mediaItemCount) { android.widget.Toast.makeText(context, "Queue is empty", android.widget.Toast.LENGTH_SHORT).show(); return }
        val ids = (listOf(current) + QueueTraversal.upcoming(player)).map { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        val local = ids.filterNotNull().distinct()
        if (local.isEmpty()) { android.widget.Toast.makeText(context, "No local songs to save", android.widget.Toast.LENGTH_SHORT).show(); return }
        val input = EditText(context).apply { setSingleLine(); hint = "Playlist name" }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Save queue as playlist")
            .setMessage("Includes the current song and upcoming local songs in playback order. Repeated songs and temporary shared audio are omitted.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim().take(120)
            if (name.isEmpty()) { input.error = "Enter a name"; return@setOnClickListener }
            dialog.dismiss()
            val app = context.applicationContext
            val worker = Executors.newSingleThreadExecutor()
            worker.execute {
                val ok = runCatching { LibraryDb(app).use { db -> db.createPlaylistWithTracks(name, local) } }.isSuccess
                android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, if (ok) "Queue saved as playlist" else "Couldn't save playlist", android.widget.Toast.LENGTH_SHORT).show() }
                worker.shutdown()
            }
        } }; dialog.show()
    }
}
