package com.tunelyst.player

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService

/** Delete only after explicit confirmation; never silently remove just a database entry. */
internal class MediaBatchActions(private val activity: MainActivity, private val io: ExecutorService,
    private val notify: (String) -> Unit, private val refresh: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var pending = mutableListOf<Uri>()
    private var waiting = false
    private var deleted = 0
    private var failed = 0
    private var closed = false
    private val requestCode = 4218
    private fun uri(track: Track): Uri {
        val volume = track.volume.takeUnless { it.isBlank() || it == "unknown" } ?: return track.uri
        return ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), track.id)
    }
    fun share(songs: List<Track>) {
        val uris = songs.distinctBy { it.id }.map { uri(it) }
        if (uris.isEmpty()) return
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri("Audio", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "Share songs")) }
            .onFailure { notify("Couldn't open a sharing app") }
    }
    fun confirmDelete(songs: List<Track>) {
        if (waiting || pending.isNotEmpty()) { notify("A deletion is already in progress"); return }
        val uris = songs.distinctBy { it.id }.map { uri(it) }
        if (uris.isEmpty()) return
        MaterialAlertDialogBuilder(activity).setTitle("Delete ${uris.size} ${if (uris.size == 1) "song" else "songs"} from device?")
            .setMessage("This deletes the actual audio files from your device, not just the playlist. Android may ask for permission. This cannot be undone by Tunelyst.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                pending = uris.toMutableList(); deleted = 0; failed = 0; advance()
            }.show()
    }
    private fun advance() {
        if (closed || waiting) return
        if (pending.isEmpty()) { finish(); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val batch = pending.take(500)
            try {
                val sender = MediaStore.createDeleteRequest(activity.contentResolver, batch).intentSender
                waiting = true
                activity.startIntentSenderForResult(sender, requestCode, null, 0, 0, 0)
            } catch (_: Exception) { waiting = false; failed += batch.size; repeat(batch.size) { pending.removeAt(0) }; advance() }
        } else deleteOne(false)
    }
    private fun deleteOne(approved: Boolean) {
        val target = pending.firstOrNull() ?: return
        io.execute {
            var count = 0; var permission: android.content.IntentSender? = null
            try { count = activity.contentResolver.delete(target, null, null) }
            catch (e: RecoverableSecurityException) { if (!approved) permission = e.userAction.actionIntent.intentSender }
            catch (_: Exception) { }
            main.post {
                if (!closed && pending.firstOrNull() == target) {
                    if (permission != null) {
                        try { waiting = true; activity.startIntentSenderForResult(permission, requestCode, null, 0, 0, 0) }
                        catch (_: Exception) { waiting = false; failed++; pending.removeAt(0); advance() }
                    } else { if (count > 0) deleted++ else failed++; pending.removeAt(0); advance() }
                }
            }
        }
    }
    fun onActivityResult(request: Int, result: Int): Boolean {
        if (request != requestCode || !waiting) return false
        waiting = false
        if (result != Activity.RESULT_OK) {
            failed += pending.size; pending.clear(); finish(); return true
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val count = minOf(500, pending.size); deleted += count; repeat(count) { pending.removeAt(0) }; advance()
        } else deleteOne(true)
        return true
    }
    private fun finish() {
        if (deleted + failed > 0) notify("Deleted $deleted; not deleted $failed")
        if (deleted > 0) refresh()
        deleted = 0; failed = 0
    }
    fun save(out: Bundle) {
        out.putStringArrayList("batch.pending", ArrayList(pending.map { it.toString() }))
        out.putBoolean("batch.waiting", waiting); out.putInt("batch.deleted", deleted); out.putInt("batch.failed", failed)
    }
    fun restore(state: Bundle) {
        pending = state.getStringArrayList("batch.pending")?.map { Uri.parse(it) }?.toMutableList() ?: mutableListOf()
        waiting = state.getBoolean("batch.waiting"); deleted = state.getInt("batch.deleted"); failed = state.getInt("batch.failed")
        if (!waiting && pending.isNotEmpty()) {
            pending.clear(); deleted = 0; failed = 0
            notify("Deletion was interrupted; refresh the library to check which files remain")
            refresh()
        }
    }
    fun close() { closed = true }
}
