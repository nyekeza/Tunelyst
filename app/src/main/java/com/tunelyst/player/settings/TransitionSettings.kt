package com.tunelyst.player

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat

/** Independent controls for the two kinds of overlap. Existing preference is read, never erased. */
internal object TransitionSettings {
    fun label(context: Context): String {
        val manual = CrossfadePreferences.duration(context, false); val auto = CrossfadePreferences.duration(context, true)
        return if (manual == 0 && auto == 0) "Gapless · crossfade off" else
            "Manual: ${if (manual == 0) "off" else "$manual ms"} · Automatic: ${if (auto == 0) "off" else "$auto ms"}"
    }
    fun choose(activity: AppCompatActivity, changed: () -> Unit) {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val info = TextView(activity).apply { text = "Choose which track changes overlap. Turn both off for normal gapless playback."; textSize = 14f }
        content.addView(info)
        fun section(title: String, automatic: Boolean): Pair<SwitchCompat, EditText> {
            val toggle = SwitchCompat(activity).apply {
                text = title; isChecked = CrossfadePreferences.enabled(activity, automatic)
                contentDescription = "$title crossfade"
            }
            content.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            val caption = TextView(activity).apply { text = "Crossfade length (milliseconds)"; textSize = 14f }
            content.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val number = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(5))
                setSingleLine(); setText(CrossfadePreferences.length(activity, automatic).toString()); isEnabled = toggle.isChecked
                contentDescription = "$title crossfade length in milliseconds"
            }
            caption.isEnabled = toggle.isChecked
            toggle.setOnCheckedChangeListener { _, checked -> number.isEnabled = checked; caption.isEnabled = checked }
            content.addView(number)
            return toggle to number
        }
        val (manual, manualLength) = section("Manual track switching", false)
        val (automatic, autoLength) = section("Automatic track switching", true)
        val scroll = ScrollView(activity).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Track transitions").setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton("About") { _, _ ->
                MaterialAlertDialogBuilder(activity).setTitle("Crossfade")
                    .setMessage("Manual crossfade applies when changing to another track. Automatic crossfade starts before the current track ends. Set each length from 1 to 10000 milliseconds. A manual switch may wait briefly for the second stream; if it cannot be prepared, playback continues normally. Turn both off for gapless when supported.")
                    .setPositiveButton("OK", null).show()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun value(field: EditText, enabled: Boolean): Int? {
                    val n = field.text.toString().toIntOrNull()
                    if (n != null && n in 1..CrossfadePreferences.MAX_MS) return n
                    if (enabled) field.error = "Enter 1 to 10000 milliseconds"
                    return if (enabled) null else CrossfadePreferences.DEFAULT_MS
                }
                val m = value(manualLength, manual.isChecked) ?: return@setOnClickListener
                val a = value(autoLength, automatic.isChecked) ?: return@setOnClickListener
                activity.getSharedPreferences(CrossfadePreferences.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(CrossfadePreferences.MANUAL_ENABLED, manual.isChecked).putBoolean(CrossfadePreferences.AUTOMATIC_ENABLED, automatic.isChecked)
                    .putInt(CrossfadePreferences.MANUAL_LENGTH, m).putInt(CrossfadePreferences.AUTOMATIC_LENGTH, a).apply()
                dialog.dismiss(); changed()
            }
        }
        dialog.show()
    }
}
