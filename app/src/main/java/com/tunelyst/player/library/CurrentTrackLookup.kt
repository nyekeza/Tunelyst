package com.tunelyst.player

/** One-entry lookup for immutable activity library snapshots, including misses. */
internal class CurrentTrackLookup {
    private var source: List<Track>? = null
    private var lastId: Long? = null
    private var last: Track? = null

    fun find(tracks: List<Track>, id: Long): Track? {
        if (source === tracks && lastId == id) return last
        val found = tracks.firstOrNull { it.id == id }
        source = tracks; lastId = id; last = found
        return found
    }
    fun clear() { source = null; lastId = null; last = null }
}
