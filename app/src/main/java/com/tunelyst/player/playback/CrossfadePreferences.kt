package com.tunelyst.player

import android.content.Context

/** Playback-only preference access; no UI classes are loaded by the service. */
internal object CrossfadePreferences {
    const val PREFS = "nyimbo_settings"
    const val LEGACY_MS = "crossfade_ms"
    const val MANUAL_ENABLED = "crossfade_v2_manual_enabled"
    const val AUTOMATIC_ENABLED = "crossfade_v2_automatic_enabled"
    const val MANUAL_LENGTH = "crossfade_v2_manual_ms"
    const val AUTOMATIC_LENGTH = "crossfade_v2_automatic_ms"
    const val MAX_MS = 10_000
    const val DEFAULT_MS = 3500

    fun affects(key: String?): Boolean = key == LEGACY_MS || key == MANUAL_ENABLED ||
        key == AUTOMATIC_ENABLED || key == MANUAL_LENGTH || key == AUTOMATIC_LENGTH

    fun length(context: Context, automatic: Boolean): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = runCatching { p.getInt(LEGACY_MS, DEFAULT_MS) }.getOrDefault(DEFAULT_MS)
        val fallback = if (legacy in 1..MAX_MS) legacy else DEFAULT_MS
        val value = runCatching { p.getInt(if (automatic) AUTOMATIC_LENGTH else MANUAL_LENGTH, fallback) }.getOrDefault(fallback)
        return if (value in 1..MAX_MS) value else fallback
    }
    // The prior experimental feature is not silently re-enabled after a rollback.
    fun enabled(context: Context, automatic: Boolean): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { p.getBoolean(if (automatic) AUTOMATIC_ENABLED else MANUAL_ENABLED, false) }.getOrDefault(false)
    }
    fun duration(context: Context, automatic: Boolean): Int = if (enabled(context, automatic)) length(context, automatic) else 0
}
