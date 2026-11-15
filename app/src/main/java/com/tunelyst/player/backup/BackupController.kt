package com.tunelyst.player

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/** User-selected local document export/import; import merges instead of deleting data. */
internal class BackupController(private val activity: AppCompatActivity, private val io: Executor, private val tracks: () -> List<Track>, private val onImported: () -> Unit) {
    private val export = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val snapshot = tracks().toList()
            io.execute {
                val saved = runCatching {
                    val stream = activity.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                    LibraryDb(activity.applicationContext).use { db -> LibraryBackup.write(db, snapshot, stream) }
                }
                activity.runOnUiThread { if (!activity.isDestroyed) {
                    val omitted = saved.getOrNull()
                    if (omitted != null && omitted > 0) MaterialAlertDialogBuilder(activity).setTitle("Backup exported")
                        .setMessage("$omitted references to songs not currently in your scanned library could not be included. Reconnect the storage or restore the missing songs and export again if you need them.")
                        .setPositiveButton("OK", null).show()
                    else Toast.makeText(activity, if (saved.isSuccess) "Backup exported" else "Couldn't export backup", Toast.LENGTH_LONG).show()
                } }
            }
        }
    }
    private val import = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val snapshot = tracks().toList()
            io.execute {
                val parsed = runCatching {
                    val stream = activity.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                    val backup = LibraryBackup.read(stream, snapshot)
                    LibraryDb(activity.applicationContext).use { db -> db.mergeBackup(backup.favorites, backup.stats, backup.playlists) }
                    backup
                }
                activity.runOnUiThread { if (!activity.isDestroyed) {
                    if (parsed.isSuccess) {
                        val resultValue = parsed.getOrThrow()
                        onImported()
                        MaterialAlertDialogBuilder(activity).setTitle("Backup imported")
                            .setMessage("Merged likes and history; ${resultValue.playlists.size} playlists added as imported copies. ${resultValue.skipped} song references could not be matched and were skipped.")
                            .setPositiveButton("OK", null).show()
                    } else Toast.makeText(activity, "Couldn't import backup; no data was changed", Toast.LENGTH_LONG).show()
                } }
            }
        }
    }
    fun export() {
        if (tracks().isEmpty()) { Toast.makeText(activity, "Scan your library first", Toast.LENGTH_SHORT).show(); return }
        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "tunelyst-backup.json"))
    }
    fun import() {
        if (tracks().isEmpty()) { Toast.makeText(activity, "Scan your library first", Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(activity).setTitle("Import local backup?")
            .setMessage("Likes and history will be merged. Playlists are added as imported copies. Songs without a clear match are skipped. Your music files are not changed.")
            .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                import.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"))
            }.show()
    }
}
