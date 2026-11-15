package com.tunelyst.player

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Persists appearance and playback preferences and owns their selection dialogs. */
class TunelystSettings(private val activity: AppCompatActivity) {
    private val preferences get() = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun forceNightMode() = AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    fun applyTheme() {
        if (useDeviceColors()) {
            DynamicColors.applyToActivityIfAvailable(activity)
            // Android 14+ supplies the selected palette as semantic role resources;
            // Material 1.9's older fixed tone mapping can differ from these roles.
            if (Build.VERSION.SDK_INT >= 34) activity.theme.applyStyle(R.style.NyimboDeviceColors, true)
        }
    }
    fun useDeviceColors(): Boolean = preferences.getBoolean("dynamic_colors", false)
    /** Check the system roles when resuming after the user changes the palette. */
    fun paletteFingerprint(): List<Int> {
        if (!useDeviceColors() || Build.VERSION.SDK_INT < 31) return emptyList()
        val ids = if (Build.VERSION.SDK_INT >= 34) intArrayOf(
            android.R.color.system_primary_dark, android.R.color.system_on_primary_dark,
            android.R.color.system_primary_container_dark, android.R.color.system_on_primary_container_dark,
            android.R.color.system_secondary_dark, android.R.color.system_tertiary_dark
        ) else intArrayOf(android.R.color.system_accent1_200, android.R.color.system_accent1_700,
            android.R.color.system_accent1_100, android.R.color.system_accent2_200, android.R.color.system_accent3_200)
        return ids.map { androidx.core.content.ContextCompat.getColor(activity, it) }
    }
    fun deviceColorsLabel(): String {
        val enabled = useDeviceColors()
        return when {
            Build.VERSION.SDK_INT < 31 -> "${if (enabled) "On" else "Off"} · available on supported Android 12+ devices"
            enabled -> "On · device accents on black when supported"
            else -> "Off · use default accents on black"
        }
    }
    fun toggleDeviceColors() {
        val enabled = preferences.getBoolean("dynamic_colors", false)
        preferences.edit().putBoolean("dynamic_colors", !enabled).apply()
        activity.recreate()
    }
    fun speed(): Float = savedSpeed(activity)
    fun chooseSpeed(onChanged: (Float) -> Unit) {
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val selected = speeds.indices.firstOrNull { speeds[it] == speed() } ?: 0
        MaterialAlertDialogBuilder(activity).setTitle("Playback speed")
            .setSingleChoiceItems(arrayOf("0.75×", "1× Normal", "1.25×", "1.5×", "2×"), selected) { dialog, which ->
                val value = speeds[which]
                preferences.edit().putFloat("playback_speed", value).apply()
                dialog.dismiss()
                onChanged(value)
            }.setNegativeButton("Cancel", null).show()
    }
    companion object {
        private const val PREFERENCES = "nyimbo_settings"
        fun savedSpeed(context: Context): Float = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getFloat("playback_speed", 1f)
    }
}
