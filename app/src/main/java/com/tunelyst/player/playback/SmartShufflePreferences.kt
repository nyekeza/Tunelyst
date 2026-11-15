package com.tunelyst.player

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

data class ShuffleWeights(val likes: Double = 1.0, val recent: Double = 1.0, val skips: Double = 1.0)

/** Normal keeps the original shuffle behavior; preferences take effect on the next mix. */
internal class SmartShufflePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_shuffle_weights", Context.MODE_PRIVATE)
    fun get() = ShuffleWeights(prefs.getFloat("likes", 1f).toDouble(), prefs.getFloat("recent", 1f).toDouble(), prefs.getFloat("skips", 1f).toDouble())
    fun choose(activity: AppCompatActivity) {
        val keys = arrayOf("likes", "recent", "skips")
        val names = arrayOf("Liked songs", "Recent plays", "Skipped songs")
        MaterialAlertDialogBuilder(activity).setTitle("Smart Shuffle controls")
            .setItems(names) { _, index ->
                val values = floatArrayOf(0f, 0.5f, 1f, 1.5f)
                val labels = arrayOf("Off", "Low", "Normal", "Strong")
                val selected = values.indices.firstOrNull { values[it] == prefs.getFloat(keys[index], 1f) } ?: 2
                MaterialAlertDialogBuilder(activity).setTitle(names[index])
                    .setSingleChoiceItems(labels, selected) { dialog, which ->
                        prefs.edit().putFloat(keys[index], values[which]).apply(); dialog.dismiss()
                        android.widget.Toast.makeText(activity, "Applies to the next Smart Shuffle mix", android.widget.Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }
}
