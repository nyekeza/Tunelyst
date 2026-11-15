package com.tunelyst.player

/** Songs lead the combined search; category filters show all matching collections. */
internal object SearchResultsLayout {
    val categories = listOf("All", "Songs", "Artists", "Albums")
    fun rows(index: LibraryIndex, songs: List<Track>, query: String, open: (String, List<Track>) -> Unit): List<LibraryListAdapter.Row> =
        rowsForCategory(index, songs, query, "All", open)

    fun rowsForCategory(index: LibraryIndex, songs: List<Track>, query: String, category: String,
                        open: (String, List<Track>) -> Unit): List<LibraryListAdapter.Row> = buildList {
        val selected = category.takeIf { it in categories } ?: "All"
        if (selected == "All" || selected == "Songs") {
            if (songs.isNotEmpty()) {
                add(LibraryListAdapter.Row.Section("Songs"))
                songs.forEachIndexed { i, track -> add(LibraryListAdapter.Row.Song(track, i)) }
            }
        }
        if (selected == "All" || selected == "Artists") {
            val artists = index.matchingArtists(query, if (selected == "All") 3 else Int.MAX_VALUE)
            if (artists.isNotEmpty()) {
                add(LibraryListAdapter.Row.Section("Artists"))
                artists.forEach { group ->
                    val name = group.first().artist
                    add(LibraryListAdapter.Row.Collection(name, "Artist · ${group.size} local songs", group.first(), { open(name, group) }))
                }
            }
        }
        if (selected == "All" || selected == "Albums") {
            val albums = index.matchingAlbums(query, if (selected == "All") 3 else Int.MAX_VALUE)
            if (albums.isNotEmpty()) {
                add(LibraryListAdapter.Row.Section("Albums"))
                albums.forEach { group ->
                    val name = group.first().album
                    add(LibraryListAdapter.Row.Collection(name, "Album · ${group.first().artist}", group.first(), { open(name, group) }))
                }
            }
        }
    }
}
