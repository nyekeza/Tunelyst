package com.tunelyst.player

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.random.Random

/** Offline play statistics. All timestamps are epoch milliseconds. */
data class TrackStats(val plays: Int, val skips: Int, val lastPlayedMs: Long)

/**
 * Produces a weighted permutation, never adding, removing, or duplicating a track.
 * Likes and previous plays give a modest boost; skips and recent plays reduce it.
 * Artist cooldown is a best effort and relaxes when the collection lacks variety.
 */
object SmartShuffle {
    /** Keep the requested track first, then smart-order the rest of the local library. */
    fun afterSelected(selected: Track, library: List<Track>, liked: Set<Long>, stats: Map<Long, TrackStats>, random: Random = Random.Default, weights: ShuffleWeights = ShuffleWeights()): List<Track> {
        val remaining = library.distinctBy { it.id }.filterNot { it.id == selected.id }
        return listOf(selected) + order(remaining, liked, stats, random = random, previousArtists = listOf(selected.artist), weights = weights)
    }

    private const val RECENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    private const val ARTIST_COOLDOWN = 2

    private data class Candidate(val track: Track, val rank: Double)
    private data class ArtistBucket(val name: String, val candidates: ArrayDeque<Candidate>)

    fun order(
        tracks: List<Track>,
        liked: Set<Long>,
        stats: Map<Long, TrackStats>,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
        previousArtists: List<String> = emptyList(),
        weights: ShuffleWeights = ShuffleWeights()
    ): List<Track> {
        if (tracks.size < 2) return tracks.toList()
        val grouped = tracks.map { track ->
            val stat = stats[track.id]
            var weight = 1.0
            if (track.id in liked) weight += 0.65 * weights.likes.coerceIn(0.0, 1.5)
            if (stat != null) {
                val plays = stat.plays.coerceAtLeast(0)
                val skips = stat.skips.coerceAtLeast(0)
                weight += minOf(plays, 20) * 0.035
                weight *= (1.0 - (skips.toDouble() / (plays + skips + 2.0)) * 0.65 * weights.skips.coerceIn(0.0, 1.5))
                if (stat.lastPlayedMs > 0 && nowMs - stat.lastPlayedMs in 0 until RECENT_WINDOW_MS) weight *= (1.0 - 0.5 * weights.recent.coerceIn(0.0, 1.5))
            }
            weight = weight.coerceIn(0.2, 2.2)
            // Exponential race gives weighted random ordering without replacing items.
            val key = -ln(random.nextDouble().coerceAtLeast(1e-12)) / weight
            Candidate(track, key)
        }.groupBy { artistKey(it.track) }

        val buckets = PriorityQueue<ArtistBucket>(compareBy { it.candidates.first().rank })
        grouped.forEach { (name, candidates) ->
            buckets.add(ArtistBucket(name, ArrayDeque(candidates.sortedBy { it.rank })))
        }
        val recentlyUsed = ArrayDeque<String>()
        previousArtists.map { it.trim().lowercase() }.filter { it.isNotBlank() && it != "unknown artist" }.takeLast(ARTIST_COOLDOWN).forEach { recentlyUsed.addLast(it) }
        val result = ArrayList<Track>(tracks.size)
        while (buckets.isNotEmpty()) {
            val held = ArrayList<ArtistBucket>(ARTIST_COOLDOWN)
            var chosen: ArtistBucket? = null
            while (buckets.isNotEmpty()) {
                val bucket = buckets.remove()
                if (bucket.name !in recentlyUsed) { chosen = bucket; break }
                held.add(bucket)
            }
            if (chosen == null) chosen = held.removeAt(0) // Too few artists; keep playing.
            buckets.addAll(held)
            result.add(chosen.candidates.removeFirst().track)
            recentlyUsed.addLast(chosen.name)
            if (recentlyUsed.size > ARTIST_COOLDOWN) recentlyUsed.removeFirst()
            if (chosen.candidates.isNotEmpty()) buckets.add(chosen)
        }
        return result
    }
    private fun artistKey(track: Track): String {
        val artist = track.artist.trim().lowercase()
        return if (artist.isBlank() || artist == "unknown artist") "unknown:${track.id}" else artist
    }
    /** Recommend only local tracks, never alter the source collection or duplicate an existing ID. */
    fun recommendations(context: List<Track>, library: List<Track>, liked: Set<Long>, stats: Map<Long, TrackStats>,
                        nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default, weights: ShuffleWeights = ShuffleWeights()): List<Track> {
        if (context.isEmpty()) return emptyList()
        val existing = context.mapTo(HashSet()) { it.id }
        val artists = context.map { it.artist.trim().lowercase() }.filter { it.isNotEmpty() && it != "unknown artist" }.toSet()
        val albums = context.map { it.album.trim().lowercase() }.filter { it.isNotEmpty() && it != "unknown album" }.toSet()
        val candidates = library.distinctBy { it.id }.filter { t ->
            t.id !in existing && (t.artist.trim().lowercase() in artists || t.album.trim().lowercase() in albums || t.id in liked)
        }
        val limit = (context.size / 3).coerceAtLeast(1).coerceAtMost(20)
        // Related artists get a small preference while skip/recency and artist spacing still apply.
        val relatedLikes = liked + candidates.filter { it.artist.trim().lowercase() in artists }.map { it.id }
        return order(candidates, relatedLikes, stats, nowMs, random, context.takeLast(1).map { it.artist }, weights).take(limit)
    }
    /** Place at most one new local suggestion after every three existing entries. */
    fun <T> interleave(original: List<T>, suggestions: List<T>, alreadyPlayedInGroup: Int = 0): List<T> {
        val result = ArrayList<T>(original.size + suggestions.size)
        var inserted = 0
        var since = alreadyPlayedInGroup.coerceIn(0, 2)
        original.forEach { item ->
            result.add(item); since++
            if (since >= 3 && inserted < suggestions.size) { result.add(suggestions[inserted++]); since = 0 }
        }
        // For a short collection there is still room for one relevant discovery.
        if (inserted == 0 && original.isNotEmpty() && suggestions.isNotEmpty()) result.add(suggestions[inserted++])
        return result
    }

}
