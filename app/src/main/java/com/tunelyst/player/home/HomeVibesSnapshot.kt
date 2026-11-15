package com.tunelyst.player

import android.content.SharedPreferences

/** Persist only the displayed track IDs, never audio files, artwork or sender data. */
internal object HomeVibesSnapshot {
    private const val LIMIT = 16
    data class Saved(val vibes: List<Track>, val hasHistory: Boolean)
    fun read(prefs: SharedPreferences, day: String, refresh: Int, tracks: List<Track>, hidden: Set<Long>): Saved? {
        if (prefs.getString("stable_day", null) != day || prefs.getInt("stable_refresh", -1) != refresh) return null
        val ids = prefs.getString("stable_vibes", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.distinct()?.take(LIMIT) ?: return null
        if (ids.isEmpty()) return null
        val wanted = ids.toHashSet()
        val found = tracks.asSequence().filter { it.id in wanted && it.id !in hidden }.associateBy { it.id }
        val vibes = ids.mapNotNull(found::get)
        return vibes.takeIf { it.isNotEmpty() }?.let { Saved(it, prefs.getBoolean("stable_history", false)) }
    }
    fun stabilize(saved: Saved?, content: HomeContent, hidden: Set<Long>): HomeContent {
        if (saved == null) return content
        val valid = (saved.vibes + content.vibes).asSequence().filter { it.id !in hidden }.distinctBy { it.id }.take(LIMIT).toList()
        return content.copy(vibes = valid)
    }
    fun write(prefs: SharedPreferences, day: String, refresh: Int, content: HomeContent) {
        val ids = content.vibes.take(LIMIT).joinToString(",") { it.id.toString() }
        if (prefs.getString("stable_day", null) == day && prefs.getInt("stable_refresh", -1) == refresh &&
            prefs.getString("stable_vibes", null) == ids && prefs.getBoolean("stable_history", false) == content.hasPlayHistory) return
        prefs.edit().putString("stable_day", day).putInt("stable_refresh", refresh).putString("stable_vibes", ids)
            .putBoolean("stable_history", content.hasPlayHistory).apply()
    }
}
