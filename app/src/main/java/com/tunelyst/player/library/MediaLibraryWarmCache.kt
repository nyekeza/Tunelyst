package com.tunelyst.player

/** Process-local snapshot; no Context or View is retained. MediaStore remains authoritative. */
internal object MediaLibraryWarmCache {
    // Avoid retaining a very large index in a background playback process on low-memory devices.
    private const val MAX_TRACKS = 20_000
    internal data class Snapshot(val all: List<Track>, val visible: List<Track>, val index: LibraryIndex, val filters: LibraryFilters.Config)
    @Volatile private var latest: Snapshot? = null
    fun get(filters: LibraryFilters.Config): Snapshot? = latest?.takeIf { it.filters == filters }
    fun put(snapshot: Snapshot) { latest = snapshot.takeIf { it.all.size <= MAX_TRACKS } }
    fun clear() { latest = null }
}
