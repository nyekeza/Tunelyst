package com.tunelyst.player

import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.Executor

/** Recomputes offline recommendations off the UI thread; stale results are discarded. */
internal class HomeContentCache(private val worker: Executor) {
    private data class Key(val tracks: List<Track>, val favorites: Set<Long>, val history: Map<Long, TrackStats>, val day: LocalDate, val refresh: Int, val avoid: Set<Long>, val hidden: Set<Long>)
    private data class Entry(val key: Key, val content: HomeContent)
    companion object {
        // One process-local entry, no Context, View, player or Activity. Bound by library size.
        private const val MAX_TRACKS = 20_000
        private var warm: Entry? = null
        fun clearWarm() { warm = null }
    }
    private val main = Handler(Looper.getMainLooper())
    private var cachedKey: Key? = null
    private var cached: HomeContent? = null
    private var pending: Key? = null
    private var failedKey: Key? = null
    val hasFailure: Boolean get() = failedKey != null
    private var generation = 0
    private var closed = false

    /** Before history has loaded, reuse only a matching previously displayed selection. */
    fun previous(tracks: List<Track>, favorites: Set<Long>, refresh: Int, avoid: Set<Long>, hidden: Set<Long>): HomeContent? {
        val entry = warm ?: return null
        val key = entry.key
        return entry.content.takeIf { key.tracks == tracks && key.favorites == favorites && key.day == LocalDate.now() &&
            key.refresh == refresh && key.avoid == avoid && key.hidden == hidden }
    }
    fun get(tracks: List<Track>, favorites: Set<Long>, history: Map<Long, TrackStats>, refresh: Int = 0, avoid: Set<Long> = emptySet(), hidden: Set<Long> = emptySet(), updated: () -> Unit): HomeContent? {
        val day = LocalDate.now()
        // Compare against already-frozen keys first. Copy inputs only for a genuinely new job.
        fun Key.matches() = this.tracks == tracks && this.favorites == favorites && this.history == history &&
            this.day == day && this.refresh == refresh && this.avoid == avoid && this.hidden == hidden
        if (cachedKey?.matches() == true) {
            if (pending != null) { pending = null; generation++ }
            return cached
        }
        val exactWarm = warm?.takeIf { it.key.matches() }
        if (exactWarm != null) {
            if (pending != null) { pending = null; generation++ }
            cachedKey = exactWarm.key; cached = exactWarm.content
            return exactWarm.content
        }
        if (!closed && pending?.matches() != true && failedKey?.matches() != true) {
            // Inputs can be mutable; background work always sees an immutable snapshot.
            val key = Key(tracks.toList(), favorites.toSet(), history.toMap(), day, refresh, avoid.toSet(), hidden.toSet())
            failedKey = null
            pending = key; val request = ++generation
            worker.execute {
                val result = runCatching { HomeRecommendations.build(key.tracks, key.favorites, key.history, System.currentTimeMillis(), key.refresh, key.avoid, key.hidden) }
                main.post {
                    if (!closed && request == generation) {
                        pending = null
                        failedKey = if (result.isFailure) key else null
                        result.onSuccess { value ->
                            cachedKey = key; cached = value
                            warm = if (key.tracks.size <= MAX_TRACKS && key.favorites.size <= MAX_TRACKS && key.history.size <= MAX_TRACKS && key.avoid.size <= MAX_TRACKS && key.hidden.size <= MAX_TRACKS) Entry(key, value) else null
                        }
                        updated()
                    }
                }
            }
        }
        val previous = cached?.takeIf { cachedKey?.tracks == tracks && cachedKey?.hidden == hidden && cachedKey?.day == day && cachedKey?.refresh == refresh && cachedKey?.avoid == avoid }
        return previous ?: warm?.takeIf { it.key.tracks == tracks && it.key.hidden == hidden && it.key.favorites == favorites && it.key.day == day && it.key.refresh == refresh && it.key.avoid == avoid }?.content
    }
    fun invalidate() { generation++; cachedKey = null; cached = null; pending = null; failedKey = null; clearWarm() }
    fun close() { closed = true; generation++; cached = null; pending = null }
}
