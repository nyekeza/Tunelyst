package com.tunelyst.player

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemClock
import android.provider.MediaStore
import java.util.concurrent.Executor

/** Coordinates cached startup, MediaStore observation and serialized refreshes. */
internal class LibraryLoadController(private val host: Host) {
    interface Host {
        val context: Context
        val io: Executor
        val main: Handler
        val isDestroyed: Boolean
        var tracks: List<Track>
        var index: LibraryIndex
        var scanned: Boolean
        var loading: Boolean
        var scanError: String?
        val query: String
        val screen: String
        val groupIds: List<Long>?
        fun hasAccess(): Boolean
        fun render()
        fun refreshHomeSilently(artworkInvalidated: Boolean): Boolean = false
        fun invalidateHomeContent() { HomeContentCache.clearWarm() }
        fun scheduleSearch(query: String)
        fun invalidateArtwork()
    }
    val filters by lazy { LibraryFilters(host.context) }
    var allTracks: List<Track> = emptyList()
        private set
    private val cache by lazy { MediaLibraryCache(host.context.applicationContext) }
    private var scanRunning = false
    private var scanAgain = false
    private var cacheRestorePending = false
    private var cacheRestoreFilters: LibraryFilters.Config? = null
    val isCacheRestorePending: Boolean get() = cacheRestorePending
    private var pendingExplicitScan = false
    private var pendingArtworkRefresh = false
    private var lastLibraryScanAt = 0L
    private var observerRegistered = false
    private var started = false
    private var mediaDirty = false
    private val mediaRefresh = Runnable {
        if (started && mediaDirty && host.hasAccess()) {
            mediaDirty = false; scan(silent = true, refreshArtwork = true)
        }
    }
    private val observer = object : ContentObserver(host.main) {
        override fun onChange(selfChange: Boolean) {
            mediaDirty = true; host.main.removeCallbacks(mediaRefresh)
            if (started) host.main.postDelayed(mediaRefresh, 900)
        }
    }
    fun onStart() {
        started = true
        if (!observerRegistered) {
            host.context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            host.context.contentResolver.registerContentObserver(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true, observer)
            observerRegistered = true
        }
        if (mediaDirty && host.hasAccess()) { host.main.removeCallbacks(mediaRefresh); host.main.post(mediaRefresh) }
        else if (host.scanned && !scanRunning && !cacheRestorePending && host.hasAccess() && SystemClock.elapsedRealtime() - lastLibraryScanAt >= 60_000L) scan(silent = true)
    }
    fun onStop() { started = false; host.main.removeCallbacks(mediaRefresh) }
    fun close() {
        onStop()
        if (observerRegistered) { host.context.contentResolver.unregisterContentObserver(observer); observerRegistered = false }
    }
    fun loadOnStart(): Unit = with(host) {
        if (!hasAccess()) { MediaLibraryWarmCache.clear(); invalidateHomeContent(); loading = false; render(); return@with }
        val warm = MediaLibraryWarmCache.get(filters.snapshot())
        if (warm != null) {
            allTracks = warm.all; tracks = warm.visible; index = warm.index
            scanned = true; loading = false; scanError = null
            if (query.isNotBlank()) scheduleSearch(query)
            render()
            scan(silent = true)
            return@with
        }
        cacheRestorePending = true
        loading = true; scanError = null; render()
        io.execute {
            val restored = runCatching {
                val config = filters.snapshot()
                cache.read()?.let { Triple(it, filters.apply(it, config), config) }
            }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (!hasAccess()) { cacheRestorePending = false; MediaLibraryWarmCache.clear(); invalidateHomeContent(); loading = false; render(); return@post }
                if (restored == null) { finishCachedStartup(null); return@post }
                val (cached, visible, config) = restored
                if (filters.snapshot() != config) { finishCachedStartup(null); return@post }
                cacheRestoreFilters = config
                allTracks = cached
                tracks = visible; scanned = true; loading = false; scanError = null
                io.execute {
                    val indexed = runCatching { LibraryIndex(visible) }.getOrNull()
                    main.post { if (!isDestroyed) finishCachedStartup(indexed) }
                }
                render()
            }
        }
    }
    private fun finishCachedStartup(indexed: LibraryIndex?): Unit = with(host) {
        cacheRestorePending = false
        if (!hasAccess()) { MediaLibraryWarmCache.clear(); invalidateHomeContent(); loading = false; render(); return }
        val config = cacheRestoreFilters
        cacheRestoreFilters = null
        val explicit = pendingExplicitScan
        val refreshArtwork = pendingArtworkRefresh
        pendingExplicitScan = false; pendingArtworkRefresh = false; scanAgain = false
        if (indexed == null || !scanned || config == null || config != filters.snapshot()) { scanned = false; scan(refreshArtwork = refreshArtwork); return }
        index = indexed
        MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(allTracks, tracks, indexed, config))
        if (query.isNotBlank()) scheduleSearch(query)
        if (groupIds == null && (screen == "Library" || (screen == "Search" && query.isNotBlank()))) render()
        scan(silent = !explicit, refreshArtwork = refreshArtwork)
    }
    private data class ScanResult(val all: List<Track>, val songs: List<Track>, val index: LibraryIndex?, val filters: LibraryFilters.Config,
                                  val rawChanged: Boolean, val visibleChanged: Boolean)
    fun onAccessRevoked() { MediaLibraryWarmCache.clear(); host.invalidateHomeContent() }
    fun scan(silent: Boolean = false, refreshArtwork: Boolean = false): Unit = with(host) {
        if (!hasAccess()) return
        if (cacheRestorePending) {
            scanAgain = true; pendingExplicitScan = pendingExplicitScan || !silent
            pendingArtworkRefresh = pendingArtworkRefresh || refreshArtwork; return
        }
        if (scanRunning) {
            scanAgain = true; pendingArtworkRefresh = pendingArtworkRefresh || refreshArtwork
            return
        }
        scanRunning = true
        if (!silent || !scanned) { loading = true; scanError = null; render() }
        val previousTracks = tracks
        val previousAll = allTracks
        io.execute {
            val result = runCatching {
                val all = LibraryScanCoordinator.scan(context.applicationContext)
                val config = filters.snapshot()
                val songs = filters.apply(all, config)
                val rawChanged = all != previousAll
                val visibleChanged = if (songs === all && previousTracks === previousAll) rawChanged else songs != previousTracks
                ScanResult(all, songs, if (visibleChanged) LibraryIndex(songs) else null, config, rawChanged, visibleChanged)
            }
            main.post {
                if (!isDestroyed) {
                    scanRunning = false; loading = false
                    if (!hasAccess()) {
                        MediaLibraryWarmCache.clear(); invalidateHomeContent(); allTracks = emptyList(); tracks = emptyList(); index = LibraryIndex(emptyList())
                        scanned = false; scanError = null; scanAgain = false; pendingArtworkRefresh = false; render(); return@post
                    }
                    var needsRender = false
                    var artworkInvalidated = false
                    result.onSuccess { (all, songs, newIndex, config, scannedRawChanged, scannedVisibleChanged) ->
                        if (config != filters.snapshot()) { scanAgain = true; pendingArtworkRefresh = pendingArtworkRefresh || refreshArtwork; return@onSuccess }
                        // Ordinarily these snapshots cannot change during a serialized scan.
                        // Recheck if a lifecycle/permission event replaced either reference.
                        val rawChanged = if (allTracks === previousAll) scannedRawChanged else all != allTracks
                        val changed = if (tracks === previousTracks) scannedVisibleChanged else songs != tracks
                        val firstScan = !scanned
                        if (rawChanged) allTracks = all
                        if (changed) tracks = songs
                        if (newIndex != null) index = newIndex
                        scanned = true; scanError = null; lastLibraryScanAt = SystemClock.elapsedRealtime()
                        MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(allTracks, tracks, index, config))
                        artworkInvalidated = changed || !silent || refreshArtwork
                        if (artworkInvalidated) invalidateArtwork()
                        needsRender = changed || !silent || refreshArtwork
                        if ((changed || firstScan) && query.isNotBlank()) scheduleSearch(query)
                        if (rawChanged || firstScan) io.execute { cache.write(all) }
                    }.onFailure {
                        if (refreshArtwork) mediaDirty = true
                        if (!scanned) { scanError = "Couldn't read your library. Check audio permissions and try again."; needsRender = true }
                    }
                    if (needsRender && !(silent && scanError == null && refreshHomeSilently(artworkInvalidated))) render()
                    if (scanAgain) {
                        val refresh = pendingArtworkRefresh; scanAgain = false; pendingArtworkRefresh = false
                        scan(silent = true, refreshArtwork = refresh)
                    }
                }
            }
        }
    }
}
