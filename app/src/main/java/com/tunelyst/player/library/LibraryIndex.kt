package com.tunelyst.player

/** Immutable snapshot, prepared away from the UI thread. */
internal class LibraryIndex(private val tracks: List<Track>) {
    private data class SearchRow(val track: Track, val text: String, val title: String, val artist: String, val album: String)
    private val marks = Regex("\\p{Mn}+")
    private val wordSeparators = Regex("[^\\p{L}\\p{N}]+")
    private fun normalized(value: String): String = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(marks, "")
    // SearchCoordinator invokes search on its worker; do not normalize the whole
    // library during startup before the user has searched for anything.
    private val searchRows: List<SearchRow> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        tracks.map { track ->
            val title = normalized(track.title.trim()); val artist = normalized(track.artist.trim()); val album = normalized(track.album.trim())
            SearchRow(track, "$title $artist $album", title, artist, album)
        }
    }
    private val fuzzyWords: List<List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        searchRows.map { it.text.split(wordSeparators).filter { word -> word.isNotEmpty() } }
    }
    private fun oneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                if (++edits > 1) return false
                when { a.length > b.length -> i++; a.length < b.length -> j++; else -> { i++; j++ } }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
    private val title = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::title))
    private val artist = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::artist).thenBy(String.CASE_INSENSITIVE_ORDER, Track::title))
    private val album = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::album).thenBy(String.CASE_INSENSITIVE_ORDER, Track::title))
    private val duration = tracks.sortedWith(compareBy<Track> { it.duration }.thenBy(String.CASE_INSENSITIVE_ORDER, Track::title))
    val folders: List<Pair<String, List<Track>>> = tracks.groupBy { it.volume to it.folder.ifBlank { "Unknown folder" } }.entries
        .map { (key, songs) ->
            val storage = when (key.first) { "" -> ""; "external_primary" -> "Internal storage"; "unknown" -> "Unknown storage"; else -> "Storage ${key.first}" }
            (if (storage.isEmpty()) key.second else "$storage · ${key.second}") to songs
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
    val albums: List<List<Track>> = tracks.groupBy { it.albumId to it.album }.values.map { group ->
        group.sortedWith(compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    }.sortedBy { it.first().album.lowercase() }
    val artists: List<List<Track>> = tracks.groupBy { it.artist }.values.sortedBy { it.first().artist.lowercase() }
    fun sorted(sort: String): List<Track> = when (sort) { "Artist" -> artist; "Album" -> album; "Duration" -> duration; else -> title }
    fun search(query: String): List<Track> {
        val needle = normalized(query.trim())
        if (needle.isBlank()) return emptyList()
        fun rank(row: SearchRow): Int = when {
            row.title == needle -> 0
            row.title.startsWith(needle) -> 1
            needle in row.title -> 2
            needle in row.artist -> 3
            needle in row.album -> 4
            else -> 5
        }
        val direct = searchRows.filter { needle in it.text }.sortedBy(::rank)
        if (direct.isNotEmpty() || needle.length < 4 || needle.any { !it.isLetterOrDigit() }) return direct.map { it.track }
        // Only broaden the search when there are no direct matches. Prioritize title typos.
        val words = fuzzyWords
        return searchRows.filterIndexed { index, _ -> words[index].any { word -> oneEdit(needle, word) } }
            .sortedBy { row -> if (row.title.split(wordSeparators).any { oneEdit(needle, it) }) 0 else 1 }
            .map { it.track }
    }
    fun matchingArtists(query: String, maxResults: Int = 3): List<List<Track>> {
        val needle = normalized(query.trim())
        return if (needle.isBlank()) emptyList() else artists.filter { normalized(it.first().artist).contains(needle) }.take(maxResults.coerceAtLeast(0))
    }
    fun matchingAlbums(query: String, maxResults: Int = 3): List<List<Track>> {
        val needle = normalized(query.trim())
        return if (needle.isBlank()) emptyList() else albums.filter { normalized(it.first().album).contains(needle) }.take(maxResults.coerceAtLeast(0))
    }
}
