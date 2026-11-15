package com.tunelyst.player

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Display-only filters. No MediaStore entries or user files are changed. */
internal class LibraryFilters(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("library_filters", Context.MODE_PRIVATE)
    fun excluded(): Set<String> = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
    fun minimumMs(): Long = prefs.getLong("minimum_ms", 0L)
    data class Config(val excluded: Set<String>, val minimumMs: Long)
    fun snapshot() = Config(excluded(), minimumMs())
    fun key(track: Track) = "${track.volume.ifBlank { "unknown" }}:${track.folder.trim('/')}"
    fun apply(tracks: List<Track>, config: Config = snapshot()): List<Track> {
        val excluded = config.excluded; val minimum = config.minimumMs
        if (excluded.isEmpty() && minimum <= 0L) return tracks
        val prefixes = excluded.map { it.trimEnd('/') + "/" }
        return tracks.filter { track ->
            if (track.duration > 0 && track.duration < minimum) false
            else if (excluded.isEmpty()) true
            else {
                val folder = key(track)
                folder !in excluded && prefixes.none { folder.startsWith(it) }
            }
        }
    }
    fun summary(): String = "${excluded().size} folders excluded · minimum ${minimumMs() / 1000}s"
    fun choose(activity: AppCompatActivity, allTracks: List<Track>, changed: () -> Unit) {
        val durations = longArrayOf(0, 5_000, 15_000, 30_000, 60_000, 120_000)
        val choices = arrayOf("No minimum", "5 seconds", "15 seconds", "30 seconds", "1 minute", "2 minutes")
        MaterialAlertDialogBuilder(activity).setTitle("Library filters")
            .setItems(arrayOf("Excluded folders (${excluded().size})", "Minimum audio length (${minimumMs() / 1000}s)")) { _, which ->
                if (which == 0) {
                    val folders = (allTracks.map { key(it) } + excluded()).distinct().sorted()
                    if (folders.isEmpty()) {
                        MaterialAlertDialogBuilder(activity).setTitle("No folders found").setMessage("Refresh the library after granting audio access.").setPositiveButton("OK", null).show()
                    } else {
                        val selection = excluded().toMutableSet()
                        MaterialAlertDialogBuilder(activity).setTitle("Exclude folders (no files deleted)")
                            .setMultiChoiceItems(folders.toTypedArray(), folders.map { it in selection }.toBooleanArray()) { _, index, checked ->
                                if (checked) selection.add(folders[index]) else selection.remove(folders[index])
                            }.setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                                prefs.edit().putStringSet("folders", selection.toSet()).apply(); changed()
                            }.show()
                    }
                } else MaterialAlertDialogBuilder(activity).setTitle("Minimum audio length")
                    .setSingleChoiceItems(choices, durations.indexOf(minimumMs()).coerceAtLeast(0)) { dialog, index ->
                        prefs.edit().putLong("minimum_ms", durations[index]).apply(); dialog.dismiss(); changed()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }
}
