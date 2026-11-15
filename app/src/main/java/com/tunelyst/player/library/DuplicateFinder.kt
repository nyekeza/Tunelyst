package com.tunelyst.player

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/** Heuristic candidates, not proof of duplicate files. Never deletes or modifies audio. */
internal object DuplicateFinder {
    fun find(tracks: List<Track>): List<List<Track>> {
        fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return tracks.filter { it.title.isNotBlank() && it.title != "Untitled" && it.duration > 0 }
            .groupBy { normalized(it.title) to normalized(it.artist) }.values.flatMap { candidates ->
                val groups = mutableListOf<MutableList<Track>>()
                candidates.sortedBy { it.duration }.forEach { track ->
                    val group = groups.lastOrNull()?.takeIf { abs(it.first().duration - track.duration) <= 2000 }
                    if (group == null) groups.add(mutableListOf(track)) else group.add(track)
                }
                groups.filter { it.size > 1 }
            }.sortedBy { it.first().title.lowercase(Locale.ROOT) }
    }
    fun show(activity: AppCompatActivity, io: Executor, tracks: List<Track>, open: (Track) -> Unit) {
        val snapshot = tracks.toList()
        io.execute {
            val groups = find(snapshot)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (groups.isEmpty()) MaterialAlertDialogBuilder(activity).setTitle("Likely duplicates")
                    .setMessage("No likely duplicates found in the visible library.").setPositiveButton("Close", null).show()
                else MaterialAlertDialogBuilder(activity).setTitle("Likely duplicates (${groups.size}) · review only")
                    .setItems(groups.map { "${it.first().title} · ${it.first().artist} (${it.size})" }.toTypedArray()) { _, index ->
                        val group = groups[index]
                        MaterialAlertDialogBuilder(activity).setTitle(group.first().title)
                            .setItems(group.map { "${it.volume.ifBlank { "Unknown storage" }} / ${it.folder.ifBlank { "Unknown folder" }} · ${it.duration / 1000}s" }.toTypedArray()) { _, selected -> open(group[selected]) }
                            .setNegativeButton("Close", null).show()
                    }.setNegativeButton("Close", null).show()
            }
        }
    }
}
