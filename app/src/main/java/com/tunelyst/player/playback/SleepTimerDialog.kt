package com.tunelyst.player

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object SleepTimerDialog {
    fun show(activity: AppCompatActivity, player: MediaController?, toast: (String) -> Unit) {
        val p = player ?: run { toast("Player connecting…"); return }
        fun send(ms: Long, end: Boolean, fade: Boolean) {
            val args = Bundle().apply { putLong(PlaybackService.SLEEP_MILLIS, ms); putBoolean(PlaybackService.SLEEP_END, end); putBoolean(PlaybackService.SLEEP_FADE, fade) }
            val result = p.sendCustomCommand(SessionCommand(PlaybackService.SLEEP_COMMAND, Bundle.EMPTY), args)
            result.addListener({ if (!activity.isDestroyed) {
                val ok = runCatching { result.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                toast(if (!ok) "Couldn't update sleep timer" else if (ms == 0L && !end) "Sleep timer cancelled" else if (end) "Sleep timer set for end of song" else "Sleep timer set")
            } }, activity.mainExecutor)
        }
        fun chooseFade(ms: Long, end: Boolean) {
            MaterialAlertDialogBuilder(activity).setTitle("Fade out?").setMessage("Fade over the last 10 seconds before playback pauses.")
                .setNegativeButton("No fade") { _, _ -> send(ms, end, false) }
                .setPositiveButton("Fade out") { _, _ -> send(ms, end, true) }.setNeutralButton("Cancel", null).show()
        }
        val labels = arrayOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "Custom duration", "End of current song", "Cancel sleep timer")
        MaterialAlertDialogBuilder(activity).setTitle("Sleep timer").setItems(labels) { _, index ->
            when (index) {
                in 0..3 -> chooseFade(longArrayOf(15, 30, 45, 60)[index] * 60_000L, false)
                4 -> {
                    val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Minutes (1–1440)" }
                    val dialog = MaterialAlertDialogBuilder(activity).setTitle("Custom sleep timer").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Next", null).create()
                    dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val minutes = input.text.toString().toLongOrNull()
                        if (minutes == null || minutes !in 1..1440) input.error = "Enter 1–1440 minutes" else { dialog.dismiss(); chooseFade(minutes * 60_000L, false) }
                    } }; dialog.show()
                }
                5 -> chooseFade(0, true)
                else -> send(0, false, false)
            }
        }.setNegativeButton("Close", null).show()
    }
}
