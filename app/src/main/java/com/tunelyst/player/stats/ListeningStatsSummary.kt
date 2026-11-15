package com.tunelyst.player

import java.util.Locale

/** Aggregates only persisted counts and last-play timestamps; never estimates listening time. */
internal object ListeningStatsSummary {
    data class Song(val track: Track, val plays: Int, val lastPlayedMs: Long)
    data class Artist(val name: String, val plays: Long, val songs: Int)
    data class Snapshot(
        val plays: Long, val songs: Int, val skips: Long, val lastPlayedMs: Long,
        val topSongs: List<Song>, val topArtists: List<Artist>, val recentSongs: List<Song>,
        val unavailableSongs: Int
    )
    fun build(records: Map<Long, TrackStats>, tracks: List<Track>): Snapshot {
        val plays = records.values.sumOf { it.plays.coerceAtLeast(0).toLong() }
        val skips = records.values.sumOf { it.skips.coerceAtLeast(0).toLong() }
        val played = records.count { it.value.plays > 0 }
        val last = records.values.maxOfOrNull { it.lastPlayedMs.coerceAtLeast(0) } ?: 0L
        val byId = tracks.associateBy { it.id }
        val available = records.mapNotNull { (id, stat) ->
            val track = byId[id]
            if (track == null || stat.plays <= 0) null else Song(track, stat.plays, stat.lastPlayedMs.coerceAtLeast(0))
        }
        val topSongs = available.sortedWith(compareByDescending<Song> { it.plays }
            .thenByDescending { it.lastPlayedMs }.thenBy { it.track.title.lowercase(Locale.ROOT) }.thenBy { it.track.id }).take(5)
        val recentSongs = available.filter { it.lastPlayedMs > 0 }.sortedWith(compareByDescending<Song> { it.lastPlayedMs }
            .thenBy { it.track.title.lowercase(Locale.ROOT) }.thenBy { it.track.id }).take(5)
        val artists = available.groupBy { it.track.artist.trim().ifEmpty { "Unknown artist" }.lowercase(Locale.ROOT) }
            .map { (_, songs) ->
                val name = songs.first().track.artist.trim().ifEmpty { "Unknown artist" }
                Artist(name, songs.sumOf { it.plays.toLong() }, songs.size)
            }.sortedWith(compareByDescending<Artist> { it.plays }.thenBy { it.name.lowercase(Locale.ROOT) }).take(5)
        return Snapshot(plays, played, skips, last, topSongs, artists, recentSongs, (played - available.size).coerceAtLeast(0))
    }
}
