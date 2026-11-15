package com.tunelyst.player

import kotlin.random.Random

/** Offline-only selections. Play counts come from the on-device listening history. */
data class HomeMix(val title: String, val detail: String, val tracks: List<Track>)
data class HomeArtist(val name: String, val tracks: List<Track>, val playCount: Long = 0L, val playedSongs: Int = 0)
data class HomeContent(val vibes: List<Track>, val hasPlayHistory: Boolean, val mixes: List<HomeMix>, val artists: List<HomeArtist>)

object HomeRecommendations {
    fun build(tracks: List<Track>, likedIds: Set<Long>, history: Map<Long, TrackStats>, nowMs: Long, refresh: Int = 0, avoid: Set<Long> = emptySet(), hidden: Set<Long> = emptySet()): HomeContent {
        val library = tracks.distinctBy { it.id }
        val liked = library.filter { it.id in likedIds }
        val sortedByPlays = compareByDescending<Track> { history[it.id]?.plays ?: 0 }
            .thenByDescending { history[it.id]?.lastPlayedMs ?: 0L }.thenBy { it.title.lowercase() }
        val played = library.filter { (history[it.id]?.plays ?: 0) > 0 }.sortedWith(sortedByPlays)
        val grouped = library.filter { it.artist.isNotBlank() && !it.artist.equals("Unknown artist", true) }
            .groupBy { it.artist.trim().lowercase() }
        val artists = grouped.values.map { songs ->
            val name = songs.first().artist.trim()
            val sorted = songs.sortedWith(sortedByPlays)
            HomeArtist(name, sorted, songs.sumOf { (history[it.id]?.plays ?: 0).coerceAtLeast(0).toLong() },
                songs.count { (history[it.id]?.plays ?: 0) > 0 })
        }.sortedWith(compareByDescending<HomeArtist> { it.playedSongs * 3.0 + kotlin.math.ln(1.0 + it.playCount) * 2.0 }
            .thenByDescending { it.playCount }.thenByDescending { it.tracks.size }.thenBy { it.name.lowercase() }).take(24)

        val likedArtists = liked.map { it.artist.trim().lowercase() }.filter { it != "unknown artist" }.toSet()
        val yourMix = if (liked.isEmpty()) library.take(60)
            else (liked + library.filter { it.artist.trim().lowercase() in likedArtists }).distinctBy { it.id }.take(60)
        val daySeed = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()
        val daily = library.shuffled(Random(daySeed)).take(60)
        val unplayed = library.filter { (history[it.id]?.plays ?: 0) == 0 }.shuffled(Random(daySeed xor 0x5271)).take(60)
        val rediscover = played.sortedWith(compareBy<Track> { history[it.id]?.lastPlayedMs ?: 0L }.thenBy { it.title.lowercase() }).take(60)
        val mixes = buildList<HomeMix> {
            fun addMix(title: String, detail: String, songs: List<Track>) {
                val selection = songs.distinctBy { it.id }.take(60)
                if (selection.isEmpty()) return
                val ids = selection.mapTo(HashSet()) { it.id }
                // Avoid nearly identical cards while allowing differently sized artist sets.
                val duplicate = any { existing ->
                    val other = existing.tracks.mapTo(HashSet()) { it.id }
                    val common = ids.count { it in other }
                    ids.size >= 3 && other.size >= 3 && common.toDouble() / (ids.size + other.size - common) >= 0.9
                }
                if (!duplicate) add(HomeMix(title, detail, selection))
            }
            addMix("Your mix", if (liked.isEmpty()) "Like songs to personalize" else "Likes and their artists", yourMix)
            addMix("Daily shuffle", "A new local selection each day", daily)
            if (played.isNotEmpty()) {
                addMix("On repeat", "Your most played local songs", played)
                if (rediscover.size > 1) addMix("Rediscover", "Songs you played longer ago", rediscover)
                addMix("Fresh finds", "Songs you haven't played yet", unplayed)
            }
            artists.filter { it.tracks.size >= 2 }.take(5).forEach { artist ->
                addMix("${artist.name} mix", if (artist.playCount > 0) "Based on your listening" else "Local tracks by this artist", artist.tracks)
            }
        }
        // Rotate a balanced mix of favorites, older plays and unheard songs daily.
        // IDs are unique and artist spacing is preferred when there are alternatives.
        val ordered = SmartShuffle.order(library.filter { it.id !in hidden }, likedIds, history, nowMs, Random(daySeed xor 0x31A7 xor refresh * 0x45d9f3b))
        val oldIds = rediscover.mapTo(HashSet()) { it.id }
        val pools = listOf(ordered.filter { it.id in likedIds }, ordered.filter { it.id in oldIds },
            ordered.filter { (history[it.id]?.plays ?: 0) == 0 }, ordered)
        val freshPools = pools.map { pool -> pool.filter { it.id !in avoid } }
        val freshAll = ordered.filter { it.id !in avoid }
        val used = HashSet<Long>(); val recentArtists = ArrayDeque<String>(); val vibes = mutableListOf<Track>()
        repeat(minOf(16, ordered.size)) { slot ->
            val pool = pools[slot % pools.size]
            fun pick(source: List<Track>, avoidArtist: Boolean) = source.firstOrNull { t ->
                t.id !in used && (!avoidArtist || t.artist.trim().lowercase() !in recentArtists)
            }
            // Favor tracks not shown in the previous selection, while still
            // relaxing the preference when the library is small.
            val freshPool = freshPools[slot % freshPools.size]
            val next = pick(freshPool, true) ?: pick(freshAll, true) ?: pick(freshPool, false) ?:
                pick(freshAll, false) ?: pick(pool, true) ?: pick(ordered, true) ?: pick(pool, false) ?: pick(ordered, false)
            if (next != null) {
                vibes.add(next); used.add(next.id)
                val artist = next.artist.trim().lowercase()
                if (artist.isNotBlank() && artist != "unknown artist") { recentArtists.addLast(artist); if (recentArtists.size > 2) recentArtists.removeFirst() }
            }
        }
        return HomeContent(vibes, played.isNotEmpty(), mixes, artists)
    }
}

/** Finite, offline radio: seeds first, followed by related local songs and then library fallbacks. */
object LocalRadio {
    fun build(seeds: List<Track>, library: List<Track>, liked: Set<Long>, history: Map<Long, TrackStats>,
              nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default): List<Track> {
        val byId = library.distinctBy { it.id }.associateBy { it.id }
        val starts = seeds.mapNotNull { byId[it.id] }.distinctBy { it.id }
        if (starts.isEmpty()) return emptyList()
        val used = starts.mapTo(HashSet()) { it.id }
        fun validArtist(t: Track): String? = t.artist.trim().lowercase().takeUnless { it.isBlank() || it == "unknown artist" || it == "<unknown>" }
        fun relation(t: Track): Int = starts.sumOf { seed ->
            val sameAlbum = t.albumId > 0 && seed.albumId > 0 && t.albumId == seed.albumId
            val artist = validArtist(t)
            val sameArtist = artist != null && artist == validArtist(seed)
            (if (sameAlbum) 3 else 0) + (if (sameArtist) 2 else 0)
        }
        // Group by match strength so genuine seed matches come before unrelated fallbacks.
        val groups = byId.values.filter { it.id !in used }.groupBy { relation(it) }.toSortedMap(reverseOrder())
        val result = starts.toMutableList()
        for ((_, candidates) in groups) {
            if (result.size >= 60) break
            val ordered = SmartShuffle.order(candidates, liked, history, nowMs, random,
                previousArtists = result.takeLast(2).map { it.artist })
            result.addAll(ordered.take(60 - result.size))
        }
        return result
    }
}
