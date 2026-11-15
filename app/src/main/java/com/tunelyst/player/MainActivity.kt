package com.tunelyst.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/** The collection follows the current song by media ID, even if it started elsewhere. */
internal object CollectionPlaybackState {
    enum class Action { START, RESUME, PAUSE }
    fun action(ids: Set<Long>, currentId: Long?, playing: Boolean, playWhenReady: Boolean, playbackState: Int): Action {
        if (currentId == null || currentId !in ids) return Action.START
        return if (playing || (playWhenReady && playbackState == Player.STATE_BUFFERING)) Action.PAUSE else Action.RESUME
    }
}

class MainActivity : AppCompatActivity() {
    private var bg = Color.rgb(17, 16, 23)
    private var surface = Color.rgb(38, 36, 48)
    private var accent = Color.rgb(255, 223, 0)
    private var muted = Color.rgb(171, 168, 183)
    private var onSurface = Color.WHITE
    private var onAccent = Color.BLACK
    private var tileSurface = Color.rgb(68, 49, 0)
    private var onTile = Color.WHITE
    private val settings by lazy { TunelystSettings(this) }
    private val libraryUiPreferences by lazy { LibraryUiPreferences(this) }
    private val searchPreferences by lazy { SearchPreferences(this) }
    private var paletteFingerprint: List<Int> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    // MediaStore and index work must not block history, search, or playback requests.
    private val libraryIo = Executors.newSingleThreadExecutor()
    private val currentTrackLookup = CurrentTrackLookup()
    private val homeContentCache by lazy { HomeContentCache(io) }
    private val homeSidePanel: HomeSidePanel by lazy { HomeSidePanel(object : HomeSidePanel.Host {
        override val context get() = this@MainActivity
        override val screenHost get() = this@MainActivity.screenHost
        override val insetTop get() = this@MainActivity.insetTop
        override val insetBottom get() = this@MainActivity.insetBottom
        override val surface get() = this@MainActivity.surface
        override val bg get() = this@MainActivity.bg
        override val onSurface get() = this@MainActivity.onSurface
        override val muted get() = this@MainActivity.muted
        override val accent get() = this@MainActivity.accent
        override val tracks get() = this@MainActivity.tracks
        override fun cover(track: Track, size: Int) = this@MainActivity.coverView(track, size)
        override val io get() = this@MainActivity.io
        override val main get() = this@MainActivity.main
        override val isDestroyed get() = this@MainActivity.isDestroyed
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun col() = this@MainActivity.col()
        override fun row() = this@MainActivity.row()
        override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
        override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
        override fun pressable(color: Int, radius: Int) = this@MainActivity.pressable(color, radius)
        override fun openSettings() = this@MainActivity.openSettings()
        override fun visibilityChanged() { backCallback.isEnabled = screen != "Home" || groupIds != null || homeSidePanel.isOpen }
    }) }
    private val homePage by lazy { HomePage(object : HomePageHost {
        override val context get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val topBar get() = this@MainActivity.topBar
        override val homeBrandTitle get() = this@MainActivity.homeBrandTitle
        override val insetTop get() = this@MainActivity.insetTop
        override val tracks get() = this@MainActivity.tracks
        override fun findTrack(id: Long) = currentTrackLookup.find(tracks, id)
        override val favorites get() = this@MainActivity.favorites
        override val history get() = this@MainActivity.history
        override val homeHistoryReady get() = this@MainActivity.homeHistoryReady
        override val homeHistoryFailed get() = this@MainActivity.homeHistoryFailed
        override val homeContentCache get() = this@MainActivity.homeContentCache
        override val vibePrefs get() = this@MainActivity.vibePrefs
        override var homeVibesPage: Int
            get() = this@MainActivity.homeVibesPage
            set(value) { this@MainActivity.homeVibesPage = value }
        override var currentVibes: VibesCarousel?
            get() = this@MainActivity.currentVibes
            set(value) { this@MainActivity.currentVibes = value }
        override var homeSmartButton: Button?
            get() = this@MainActivity.homeSmartButton
            set(value) { this@MainActivity.homeSmartButton = value }
        override val player get() = this@MainActivity.player
        override val playbackConnecting get() = this@MainActivity.playbackConnecting
        override val uiStarted get() = this@MainActivity.uiStarted
        override fun queueStatus(id: Long) = this@MainActivity.queueStatuses[id]
        override val screen get() = this@MainActivity.screen
        override val groupIds get() = this@MainActivity.groupIds
        override val loading get() = this@MainActivity.loading
        override val isDestroyed get() = this@MainActivity.isDestroyed
        override val bg get() = this@MainActivity.bg
        override val surface get() = this@MainActivity.surface
        override val onSurface get() = this@MainActivity.onSurface
        override val tileSurface get() = this@MainActivity.tileSurface
        override val onAccent get() = this@MainActivity.onAccent
        override val accent get() = this@MainActivity.accent
        override val muted get() = this@MainActivity.muted
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun row() = this@MainActivity.row()
        override fun col() = this@MainActivity.col()
        override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
        override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
        override fun button(text: String, action: () -> Unit) = this@MainActivity.button(text, action)
        override fun shape(color: Int, radius: Int) = this@MainActivity.shape(color, radius)
        override fun pressable(color: Int, radius: Int) = this@MainActivity.pressable(color, radius)
        override fun coverView(track: Track, size: Int) = this@MainActivity.coverView(track, size)
        override fun refreshCoverView(track: Track, view: View, size: Int) = artworkViews.refreshCover(track, view, size)
        override fun sharedArtworkView(data: ByteArray, size: Int) = this@MainActivity.sharedArtworkView(data, size)
        override fun collageView(tracks: List<Track>, size: Int) = this@MainActivity.collageView(tracks, size)
        override fun playList(tracks: List<Track>, index: Int, sourceOverride: String?) = this@MainActivity.playList(tracks, index, sourceOverride = sourceOverride)
        override fun openGroup(title: String, tracks: List<Track>, source: View) = this@MainActivity.openGroup(title, tracks, source)
        override fun vibeTrackMenu(track: Track) = this@MainActivity.vibeTrackMenu(track)
        override fun startSmartShuffle(tracks: List<Track>) = this@MainActivity.startSmartShuffle(tracks)
        override fun updateSmartShuffleUi() = this@MainActivity.updateSmartShuffleUi()
        override fun openPlayer() = this@MainActivity.openPlayer()
        override fun toast(message: String) = this@MainActivity.toast(message)
        override fun header(title: String, detail: String) = this@MainActivity.header(title, detail)
        override fun empty() = this@MainActivity.empty()
        override fun requestRender() = this@MainActivity.render()
    }) }
    private val artworkIo = Executors.newFixedThreadPool(2)
    private val artworkLoader by lazy { ArtworkLoader(this, artworkIo) }
    private val artworkGradient by lazy { ArtworkGradientRenderer(this, artworkIo, main) }
    private lateinit var db: LibraryDb
    private var tracks = emptyList<Track>()
    private var libraryIndex = LibraryIndex(emptyList())
    private val searchCoordinator by lazy {
        SearchCoordinator(io, { main.post(it) }, { task, delay -> main.postDelayed(task, delay) }, { main.removeCallbacks(it) },
            { libraryIndex }, { query }, { isDestroyed }, { screen == "Search" },
            { screen == "Search" && groupIds == null }, { render() })
    }
    private lateinit var backupController: BackupController
    private val scrollPositions = mutableMapOf<String, Int>()
    private var renderedScrollKey: String? = null
    private var scrollRestorePending = false
    private var homeVibesPage = 0
    private val vibePrefs by lazy { getSharedPreferences("instant_vibes", MODE_PRIVATE) }
    private var currentVibes: VibesCarousel? = null
    private var favorites = emptySet<Long>()
    private var loading = false
    private var scanned = false
    private var scanError: String? = null
    private var screen = "Home"
    private var playerReturnScreen = "Home"
    private var settingsReturnScreen = "Home"
    private var tab = "Songs"
    private var renderedPlayerId: String? = null
    private var history: Map<Long, TrackStats> = emptyMap()
    private var homeHistoryReady = false
    private var homeHistoryFailed = false
    private var historyRequest = 0
    private var query = ""
    private var groupTitle: String? = null
    private var groupIds: List<Long>? = null
    private var playlistId: Long? = null
    private var artistReturnToPlayer = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var playbackConnecting = true
    private var uiStarted = false
    private var pendingHomeArtworkRefresh = false
    private lateinit var screenHost: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var topChrome: LinearLayout
    private lateinit var statusScrim: View
    private lateinit var bottomDock: LinearLayout
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private lateinit var content: LinearLayout
    private lateinit var libraryScroll: ScrollView
    private lateinit var listHost: FrameLayout
    private var activeList: ListView? = null
    private var activeAdapter: LibraryListAdapter? = null
    private var lastListPlayback: Pair<Long?, Boolean>? = null
    private var queueStatuses: Map<Long, String> = emptyMap()
    private var activeListKey: String? = null
    private val listPositions = mutableMapOf<String, Pair<Int, Int>>()
    private var listRestorePending = false
    private data class RecentSongViews(val track: Track, val root: View, val title: TextView, val icons: QueueStatusIcons, val playing: PlayingIndicator, val contextLabel: String)
    private val recentSongViews = mutableListOf<RecentSongViews>()
    private lateinit var tabs: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var bottomNav: LinearLayout
    private lateinit var bottomNavHost: HorizontalScrollView
    private lateinit var bottomNavShell: FrameLayout
    private var librarySort = "Title"
    private var playlistFilter = ""
    private var playlistSort = "Playlist order"
    private lateinit var searchHeading: TextView
    private lateinit var libraryHeading: TextView
    private lateinit var searchCategoryTabs: LinearLayout
    private lateinit var searchCategoryScroll: HorizontalScrollView
    private lateinit var searchInput: EditText
    private lateinit var searchContainer: FrameLayout
    private lateinit var searchClear: ImageButton
    private var searchFilter = "All"
    private lateinit var topBar: LinearLayout
    private lateinit var homeBrandTitle: TextView
    private lateinit var queueHost: FrameLayout
    private lateinit var queuePeek: QueuePeekView
    private lateinit var miniPlayer: FrameLayout
    private lateinit var miniArt: FrameLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniNext: ImageButton
    private lateinit var miniProgress: ProgressBar
    private var miniArtKey: String? = null
    private lateinit var playerTitle: TextView
    private lateinit var playerSub: TextView
    private lateinit var playButton: ImageButton
    private lateinit var playerMenuButton: ImageButton
    private lateinit var repeatButton: RepeatControl
    private var smartButton: ImageButton? = null
    private var homeSmartButton: Button? = null
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private var seeking = false
    private var playRequest = 0
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var batchActions: MediaBatchActions
    private val sharedIntents by lazy { SharedAudioIntents(object : SharedAudioIntents.Host {
        override val activity get() = this@MainActivity
        override val player get() = this@MainActivity.player
        override fun hasAccess() = this@MainActivity.hasAccess()
        override fun requestAccess() = this@MainActivity.requestAccess()
        override fun invalidatePendingPlayback() { playRequest++ }
        override fun sendSmartOff() = sendSmartCommand("off", announce = false)
        override fun openPlayer() = this@MainActivity.openPlayer()
        override fun toast(message: String) = this@MainActivity.toast(message)
    }) }
    private val libraryLoader: LibraryLoadController by lazy { LibraryLoadController(object : LibraryLoadController.Host {
        override val context get() = this@MainActivity
        override val io get() = this@MainActivity.libraryIo
        override val main get() = this@MainActivity.main
        override val isDestroyed get() = this@MainActivity.isDestroyed
        override var tracks: List<Track>
            get() = this@MainActivity.tracks
            set(value) {
                if (this@MainActivity.tracks !== value) currentTrackLookup.clear()
                this@MainActivity.tracks = value
            }
        override var index: LibraryIndex
            get() = libraryIndex
            set(value) { libraryIndex = value }
        override var scanned: Boolean
            get() = this@MainActivity.scanned
            set(value) { this@MainActivity.scanned = value }
        override var loading: Boolean
            get() = this@MainActivity.loading
            set(value) { this@MainActivity.loading = value }
        override var scanError: String?
            get() = this@MainActivity.scanError
            set(value) { this@MainActivity.scanError = value }
        override val query get() = this@MainActivity.query
        override val screen get() = this@MainActivity.screen
        override val groupIds get() = this@MainActivity.groupIds
        override fun hasAccess() = this@MainActivity.hasAccess()
        override fun render() = this@MainActivity.render()
        override fun refreshHomeSilently(artworkInvalidated: Boolean): Boolean {
            if (screen != "Home" || groupIds != null || !homePage.canRefreshContent()) return false
            pendingHomeArtworkRefresh = pendingHomeArtworkRefresh || artworkInvalidated
            if (uiStarted) { updateHomeHero(); updateMiniPlayer(); refreshPendingHomeArtwork() }
            homePage.refreshContent()
            return true
        }
        override fun invalidateHomeContent() = homeContentCache.invalidate()
        override fun scheduleSearch(query: String) = searchCoordinator.schedule(query)
        override fun invalidateArtwork() { artworkLoader.invalidate(); artworkGradient.invalidate() }
    }) }
    private val selection: SongSelectionController by lazy { SongSelectionController(object : SongSelectionController.Host {
        override val activity get() = this@MainActivity
        override val io get() = this@MainActivity.io
        override val main get() = this@MainActivity.main
        override val db get() = this@MainActivity.db
        override val batchActions get() = this@MainActivity.batchActions
        override val scope get() = currentSelectionScope()
        override val playlistId get() = this@MainActivity.playlistId
        override fun selectionChanged(active: Boolean, count: Int) {
            if (!::selectionBar.isInitialized) return
            selectionBar.visibility = if (active) View.VISIBLE else View.GONE
            selectionCount.text = "$count selected"
            backCallback.isEnabled = active || screen != "Home" || groupIds != null || homeSidePanel.isOpen
            activeAdapter?.notifyDataSetChanged(); updatePagePadding()
        }
        override fun render() = this@MainActivity.render()
        override fun toast(message: String) = this@MainActivity.toast(message)
        override fun favoritesChanged() { favorites = db.favorites() }
        override fun playlistChanged(id: Long) { if (playlistId == id) groupIds = db.trackIds(id) }
        override fun createPlaylist(after: (Long) -> Unit) = this@MainActivity.createPlaylist(after)
    }) }
    private val playbackStarter by lazy { PlaybackStarter(object : PlaybackStarter.Host {
        override val context get() = applicationContext
        override val io get() = this@MainActivity.io
        override val main get() = this@MainActivity.main
        override val player get() = this@MainActivity.player
        override val tracks get() = this@MainActivity.tracks
        override val favorites get() = this@MainActivity.favorites
        override val screen get() = this@MainActivity.screen
        override val groupTitle get() = this@MainActivity.groupTitle
        override val groupIds get() = this@MainActivity.groupIds
        override fun nextRequest() = ++playRequest
        override fun isCurrentRequest(request: Int, player: MediaController) = !isDestroyed && request == playRequest && this@MainActivity.player === player
        override fun sendSmartCommand(mode: String, announce: Boolean) = this@MainActivity.sendSmartCommand(mode, announce)
        override fun toggleSmartShuffle() = this@MainActivity.toggleSmartShuffle()
        override fun saveSearchedSong(id: Long) = libraryUiPreferences.saveSearchedSong(id)
        override fun openPlayer() = this@MainActivity.openPlayer()
        override fun toast(message: String) = this@MainActivity.toast(message)
    }) }
    private val artworkViews by lazy { ArtworkViews(object : ArtworkViews.Host {
        override val context get() = this@MainActivity
        override val tileSurface get() = this@MainActivity.tileSurface
        override val surface get() = this@MainActivity.surface
        override val muted get() = this@MainActivity.muted
        override val loader get() = artworkLoader
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun shape(color: Int, radius: Int) = this@MainActivity.shape(color, radius)
    }) }
    private val queueController by lazy { QueueDialogController(object : QueueDialogController.Host {
        override val activity get() = this@MainActivity
        override val player get() = this@MainActivity.player
        override val tracks get() = this@MainActivity.tracks
        override val surface get() = this@MainActivity.surface
        override val onSurface get() = this@MainActivity.onSurface
        override val muted get() = this@MainActivity.muted
        override val accent get() = this@MainActivity.accent
        override val tileSurface get() = this@MainActivity.tileSurface
        override val onTile get() = this@MainActivity.onTile
        override val insetTop get() = this@MainActivity.insetTop
        override val insetBottom get() = this@MainActivity.insetBottom
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun cover(track: Track, size: Int) = coverView(track, size)
        override fun togglePlayback() = this@MainActivity.togglePlayback()
        override fun toast(message: String) = this@MainActivity.toast(message)
    }) }
    private val collectionPage by lazy { CollectionPage(object : CollectionPage.Host {
        override val activity get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val tracks get() = this@MainActivity.tracks
        override val groupIds get() = this@MainActivity.groupIds!!
        override val groupTitle get() = this@MainActivity.groupTitle
        override val screen get() = this@MainActivity.screen
        override val playlistId get() = this@MainActivity.playlistId
        override var playlistFilter: String
            get() = this@MainActivity.playlistFilter
            set(value) { this@MainActivity.playlistFilter = value }
        override var playlistSort: String
            get() = this@MainActivity.playlistSort
            set(value) { this@MainActivity.playlistSort = value }
        override val db get() = this@MainActivity.db
        override val surface get() = this@MainActivity.surface
        override val onSurface get() = this@MainActivity.onSurface
        override val muted get() = this@MainActivity.muted
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun row() = this@MainActivity.row()
        override fun button(text: String, action: () -> Unit) = this@MainActivity.button(text, action)
        override fun label(text: String, size: Float, color: Int) = this@MainActivity.label(text, size, color)
        override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
        override fun matches(track: Track) = this@MainActivity.matches(track)
        override fun addHero(title: String, tracks: List<Track>) = addCollectionHero(title, tracks)
        override fun editor(id: Long) = playlistEditor(id)
        override fun addTracks(tracks: List<Track>) = this@MainActivity.addTracks(tracks)
        override fun stateCard(title: String, detail: String) = this@MainActivity.stateCard(title, detail)
        override fun render() = this@MainActivity.render()
    }) }
    private val libraryPage by lazy { LibraryPage(object : LibraryPage.Host {
        override val activity get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val tab get() = this@MainActivity.tab
        override val index get() = libraryIndex
        override val tracks get() = this@MainActivity.tracks
        override val favorites get() = this@MainActivity.favorites
        override val db get() = this@MainActivity.db
        override val librarySort get() = this@MainActivity.librarySort
        override val onSurface get() = this@MainActivity.onSurface
        override val muted get() = this@MainActivity.muted
        override val surface get() = this@MainActivity.surface
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun row() = this@MainActivity.row()
        override fun col() = this@MainActivity.col()
        override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
        override fun button(text: String, action: () -> Unit) = this@MainActivity.button(text, action)
        override fun pressable(color: Int, radius: Int) = this@MainActivity.pressable(color, radius)
        override fun collage(list: List<Track>, size: Int) = collageView(list, size)
        override fun artworkShape(key: String) = this@MainActivity.artworkShape(key)
        override fun setSort(sort: String) { this@MainActivity.librarySort = sort; libraryUiPreferences.setLibrarySort(sort) }
        override fun jumpToTitle(letter: String) {
            setSort("Title")
            val sorted = sortedLibraryTracks()
            val index = sorted.indexOfFirst { t -> val c = t.title.trim().firstOrNull()?.uppercaseChar(); if (letter == "#") c == null || c !in 'A'..'Z' else c == letter[0] }
            render(); if (index >= 0) activeList?.post { activeList?.setSelection(index + (activeList?.headerViewsCount ?: 0)) } else toast("No songs under $letter")
        }
        override fun render() = this@MainActivity.render()
        override fun addTracks(tracks: List<Track>) = this@MainActivity.addTracks(tracks)
        override fun setRows(rows: List<LibraryListAdapter.Row>) = setLibraryRows(rows)
        override fun openGroup(name: String, tracks: List<Track>) = this@MainActivity.openGroup(name, tracks)
        override fun openPlaylist(id: Long, name: String) { playlistId = id; playlistFilter = ""; playlistSort = "Playlist order"; groupTitle = name; groupIds = db.trackIds(id); render() }
        override fun createPlaylist() = this@MainActivity.createPlaylist()
        override fun confirmDeletePlaylist(id: Long, name: String) = confirmDelete(id, name)
        override fun empty() = this@MainActivity.empty()
        override fun stateCard(title: String, detail: String) = this@MainActivity.stateCard(title, detail)
    }) }
    private val collectionHeroController by lazy { CollectionHeroController(object : CollectionHeroController.Host {
        override val activity get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val player get() = this@MainActivity.player
        override val groupIds get() = this@MainActivity.groupIds
        override val tileSurface get() = this@MainActivity.tileSurface
        override val accent get() = this@MainActivity.accent
        override val onAccent get() = this@MainActivity.onAccent
        override val insetTop get() = this@MainActivity.insetTop
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun col() = this@MainActivity.col()
        override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
        override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
        override fun collage(list: List<Track>, size: Int) = collageView(list, size)
        override fun applyGradient(track: Track?, target: View) = artworkGradient.apply(track, target, overlay = true, accent = accent)
        override fun cancelPendingPlayback() { playRequest++ }
        override fun play(list: List<Track>) = playList(list, 0, searchSelection = false, openPlayerOnStart = false)
        override fun toast(message: String) = this@MainActivity.toast(message)
    }) }
    private val bottomNavigation by lazy { BottomNavigation(object : BottomNavigation.Host {
        override val context get() = this@MainActivity
        override val bottomNav get() = this@MainActivity.bottomNav
        override val screen get() = this@MainActivity.screen
        override val settingsReturnScreen get() = this@MainActivity.settingsReturnScreen
        override val playerReturnScreen get() = this@MainActivity.playerReturnScreen
        override val accent get() = this@MainActivity.accent
        override val deviceColors get() = settings.useDeviceColors()
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun col() = this@MainActivity.col()
        override fun shape(color: Int, radius: Int) = this@MainActivity.shape(color, radius)
        override fun label(text: String, size: Float, color: Int) = this@MainActivity.label(text, size, color)
        override fun gradientIcon(resource: Int) = gradientNavigationIcon(resource)
        override fun navigate(destination: String) = this@MainActivity.navigate(destination)
    }) }
    private val songActions by lazy { SongActions(object : SongActions.Host {
        override val activity get() = this@MainActivity
        override val io get() = this@MainActivity.io
        override val db get() = this@MainActivity.db
        override val favorites get() = this@MainActivity.favorites
        override val player get() = this@MainActivity.player
        override val playlistId get() = this@MainActivity.playlistId
        override val canSelect get() = activeAdapter != null
        override val batchActions get() = this@MainActivity.batchActions
        override fun toggleFavorite(track: Track) = this@MainActivity.toggleFavorite(track)
        override fun select(track: Track) = selection.start(track)
        override fun updatePlaylist(ids: List<Long>) { groupIds = ids }
        override fun render() = this@MainActivity.render()
        override fun toast(message: String) = this@MainActivity.toast(message)
    }) }
    private val searchPage by lazy { SearchPage(object : SearchPage.Host {
        override val activity get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val query get() = this@MainActivity.query
        override val category get() = searchFilter
        override val index get() = libraryIndex
        override val coordinator get() = searchCoordinator
        override val preferences get() = searchPreferences
        override val muted get() = this@MainActivity.muted
        override fun label(text: String, size: Float, color: Int) = this@MainActivity.label(text, size, color)
        override fun button(text: String, action: () -> Unit) = this@MainActivity.button(text, action)
        override fun stateCard(title: String, detail: String) = this@MainActivity.stateCard(title, detail)
        override fun empty() = this@MainActivity.empty()
        override fun rows(rows: List<LibraryListAdapter.Row>, songs: List<Track>) = setLibraryRows(rows, songs)
        override fun openGroup(name: String, group: List<Track>) = this@MainActivity.openGroup(name, group)
        override fun setQuery(value: String) { searchInput.setText(value); searchInput.setSelection(value.length) }
        override fun recentlySearchedSongs() = renderRecentlySearchedSongs()
        override fun render() = this@MainActivity.render()
    }) }
    private val settingsPage by lazy { SettingsPage(object : SettingsPage.Host {
        override val activity get() = this@MainActivity
        override val content get() = this@MainActivity.content
        override val onSurface get() = this@MainActivity.onSurface
        override val muted get() = this@MainActivity.muted
        override val accent get() = this@MainActivity.accent
        override val loading get() = this@MainActivity.loading
        override val player get() = this@MainActivity.player
        override val settings get() = this@MainActivity.settings
        override val backup get() = this@MainActivity.backupController
        override val hiddenCount get() = vibePrefs.getStringSet("hidden", emptySet())?.size ?: 0
        override fun dp(value: Int) = this@MainActivity.dp(value)
        override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
        override fun back() { screen = settingsReturnScreen; render(); if (screen == "Home") refreshHomeHistory() }
        override fun render() = this@MainActivity.render()
        override fun hasAccess() = this@MainActivity.hasAccess()
        override fun scan() = this@MainActivity.scan()
        override fun requestAccess() = this@MainActivity.requestAccess()
        override fun filterSummary() = libraryLoader.filters.summary()
        override fun libraryFilters() = libraryLoader.filters.choose(this@MainActivity, libraryLoader.allTracks) { scan() }
        override fun shuffleSettings() = SmartShufflePreferences(this@MainActivity).choose(this@MainActivity)
        override fun sleepTimer() = chooseSleepTimer()
        override fun duplicateFinder() = DuplicateFinder.show(this@MainActivity, io, tracks) { SongDetails.show(this@MainActivity, io, it) }
        override fun clearHistory() = confirmClearHistory()
        override fun restoreHiddenSongs() { vibePrefs.edit().remove("hidden").apply(); toast("Songs restored to Instant Vibes"); render() }
        override fun toast(message: String) = this@MainActivity.toast(message)
        override fun iconButton(icon: Int, description: String, action: () -> Unit) = this@MainActivity.iconButton(icon, description, action = action)
        override fun collectionRow(title: String, detail: String, icon: Int, click: () -> Unit) = this@MainActivity.collectionRow(title, detail, click = click, iconRes = icon)
    }) }
    private var renderedSharedArtKey: Int? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)
            if (queueChanged) {
                refreshQueueStatuses()
                sharedIntents.onQueueChanged(player)
            }
            val artKey = if (SharedAudio.isShared(player.currentMediaItem)) player.mediaMetadata.artworkData?.contentHashCode() else null
            if (screen == "Player" && (renderedPlayerId != player.currentMediaItem?.mediaId || renderedSharedArtKey != artKey)) render() else updatePlayer()
            if (screen == "Home" && groupIds == null) { updateHomeHero(); updateVibePlayingState() }
            updateRecentSongStates()
            updateMiniPlayer(); updateListPlayingState(); updateCollectionPlayButton()
            if (screen == "Player" && ::queuePeek.isInitialized) queuePeek.refresh(player)
            queueController.refresh(queueChanged)
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { toast("Can't play this track. It may have been moved or use an unsupported format.") }
    }
    private val ticker = object : Runnable {
        override fun run() { if (player != null) updatePlayer(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings.forceNightMode()
        super.onCreate(savedInstanceState)
        // AppCompat initializes its theme in super.onCreate; apply the dynamic
        // overlay afterwards, before resolving colors or constructing views.
        settings.applyTheme()
        batchActions = MediaBatchActions(this, io, { message -> toast(message) }, { if (hasAccess()) scan(silent = true, refreshArtwork = true) })
        backupController = BackupController(this, io, { tracks }, {
            favorites = db.favorites(); refreshHomeHistory(); render()
        })
        savedInstanceState?.let { state ->
            scrollPositions["Home"] = state.getInt("homeScroll", 0); homeVibesPage = state.getInt("homeVibesPage", 0)
            val keys = state.getStringArrayList("listPositionKeys")
            val positions = state.getIntArray("listPositionValues")
            if (keys != null && positions != null && keys.size <= 50 && positions.size == keys.size * 2)
                keys.forEachIndexed { i, key -> listPositions[key] = positions[i * 2] to positions[i * 2 + 1] }
        }
        screen = (savedInstanceState?.getString("screen") ?: "Home").let { if (it == "Queue") "Player" else it }
        playerReturnScreen = savedInstanceState?.getString("playerReturnScreen") ?: savedInstanceState?.getString("previousScreen") ?: "Home"
        settingsReturnScreen = savedInstanceState?.getString("settingsReturnScreen") ?: "Home"
        onBackPressedDispatcher.addCallback(this, backCallback)
        tab = savedInstanceState?.getString("tab") ?: "Songs"
        query = savedInstanceState?.getString("query").orEmpty()
        searchFilter = savedInstanceState?.getString("searchFilter")?.takeIf { it in SearchResultsLayout.categories } ?: "All"
        librarySort = libraryUiPreferences.librarySort()
        groupTitle = savedInstanceState?.getString("groupTitle")
        groupIds = savedInstanceState?.getLongArray("groupIds")?.toList()
        playlistId = savedInstanceState?.getLong("playlistId", -1L)?.takeIf { it >= 0 }
        artistReturnToPlayer = savedInstanceState?.getBoolean("artistReturnToPlayer", false) ?: false
        bg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, bg)
        surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, surface)
        accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, accent)
        muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, muted)
        onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, onSurface)
        onAccent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, onAccent)
        tileSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, tileSurface)
        onTile = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, onTile)
        bg = Color.BLACK; surface = Color.rgb(22, 22, 22); onSurface = Color.WHITE; muted = Color.rgb(190, 190, 190)
        // Keep the main canvas pitch black, but use the device's actual semantic
        // primary / container roles and their matching foregrounds elsewhere.
        if (androidx.core.graphics.ColorUtils.calculateContrast(accent, bg) < 4.5) {
            accent = androidx.core.graphics.ColorUtils.blendARGB(accent, Color.WHITE, 0.3f)
        }
        if (androidx.core.graphics.ColorUtils.calculateContrast(onAccent, accent) < 4.5)
            onAccent = if (androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, accent) >= 4.5) Color.BLACK else Color.WHITE
        if (androidx.core.graphics.ColorUtils.calculateContrast(onTile, tileSurface) < 4.5)
            onTile = if (androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, tileSurface) >= 4.5) Color.WHITE else Color.BLACK
        paletteFingerprint = settings.paletteFingerprint()
        @Suppress("DEPRECATION")
        run {
            TunelystLayout.enableEdgeToEdge(window)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        }
        db = LibraryDb(this)
        favorites = db.favorites()
        buildUi()
        savedInstanceState?.let { state ->
            batchActions.restore(state)
            sharedIntents.restore(state)
        }
        if (savedInstanceState == null) sharedIntents.receive(intent)
        if (hasAccess()) loadLibraryOnStart() else render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("artistReturnToPlayer", artistReturnToPlayer)
        outState.putString("screen", screen); outState.putString("playerReturnScreen", playerReturnScreen); outState.putString("settingsReturnScreen", settingsReturnScreen)
        outState.putString("tab", tab); outState.putString("query", query); outState.putString("searchFilter", searchFilter)
        outState.putString("groupTitle", groupTitle); groupIds?.let { outState.putLongArray("groupIds", it.toLongArray()) }
        playlistId?.let { outState.putLong("playlistId", it) }
        batchActions.save(outState)
        sharedIntents.save(outState)
        rememberListPosition(); rememberScrollPosition()
        val savedLists = listPositions.entries.toList().takeLast(50)
        outState.putStringArrayList("listPositionKeys", ArrayList(savedLists.map { it.key }))
        outState.putIntArray("listPositionValues", savedLists.flatMap { listOf(it.value.first, it.value.second) }.toIntArray())
        outState.putInt("homeScroll", scrollPositions["Home"] ?: 0); outState.putInt("homeVibesPage", homeVibesPage)
        super.onSaveInstanceState(outState)
    }
    // AndroidX dispatches both the system back button and gesture on supported Android versions.
    private val backCallback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                homeSidePanel.isOpen -> homeSidePanel.back()
                selection.active -> selection.stop()
                screen == "Queue" -> openPlayer()
                screen == "Player" -> { screen = playerReturnScreen; render(); if (screen == "Home") refreshHomeHistory() }
                screen == "Settings" -> { screen = settingsReturnScreen; render(); if (screen == "Home") refreshHomeHistory() }
                groupIds != null -> closeCollection()
                screen != "Home" -> navigate("Home")
            }
        }
    }
    private fun syncConnectedPlayer(controller: MediaController) {
        if (!uiStarted || player !== controller) return
        controller.addListener(listener)
        refreshQueueStatuses()
        if (sharedIntents.hasPending) sharedIntents.playPending() else if (hasAccess()) requestQueueRecovery()
        updateListPlayingState(); updateCollectionPlayButton()
        if (screen == "Player" || screen == "Queue") render()
        else {
            updatePlayer()
            if (screen == "Home" && groupIds == null) { updateHomeHero(); updateVibePlayingState() }
        }
    }
    override fun onStart() {
        super.onStart()
        uiStarted = true
        // A paused service may have disconnected under memory pressure while stopped.
        if (player != null && player?.isConnected != true) {
            player?.removeListener(listener); player = null
            controllerFuture?.let { MediaController.releaseFuture(it) }; controllerFuture = null
        }
        playbackConnecting = player == null
        if (screen == "Home" && groupIds == null) { updateHomeHero(); updateVibePlayingState() }
        updateMiniPlayer(); updateSmartShuffleUi()
        refreshPendingHomeArtwork()
        homePage.onStart()
        libraryLoader.onStart()
        val existing = player
        if (existing != null) syncConnectedPlayer(existing)
        else if (controllerFuture == null) {
            val future = MediaController.Builder(applicationContext, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
            controllerFuture = future
            future.addListener({
                if (controllerFuture === future) {
                    val connected = try { future.get() } catch (_: Exception) {
                        controllerFuture = null; playbackConnecting = false
                        MediaController.releaseFuture(future)
                        if (uiStarted) { updatePlayer(); updateHomeHero(); toast("Unable to connect to the player") }
                        return@addListener
                    }
                    player = connected; playbackConnecting = false
                    syncConnectedPlayer(connected)
                }
            }, mainExecutor)
        }
        main.post(ticker)
    }
    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && settings.paletteFingerprint() != paletteFingerprint) {
            recreate(); return
        }
        if (::content.isInitialized) {
            if (!hasAccess()) {
                libraryLoader.onAccessRevoked(); currentTrackLookup.clear(); tracks = emptyList(); scanned = false
                if (!sharedIntents.shouldKeepPlaying(player?.currentMediaItem)) player?.stop()
                render()
            }
            else if (!scanned && !loading) scan()
            if (hasAccess()) { refreshHomeHistory(); requestQueueRecovery() }
        }
    }
    override fun onStop() {
        uiStarted = false
        libraryLoader.onStop()
        main.removeCallbacks(ticker)
        queueController.dismiss()
        // Keep the connection while this Activity remains in Recents. A paused service
        // can otherwise be destroyed and its queue reconstructed on every visit.
        player?.removeListener(listener)
        super.onStop()
    }
    override fun onDestroy() {
        player?.removeListener(listener); player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }; controllerFuture = null
        libraryLoader.close()
        searchCoordinator.close(); batchActions.close()
        homeContentCache.close(); currentTrackLookup.clear(); libraryIo.shutdown(); io.shutdown(); artworkLoader.close(); artworkIo.shutdownNow(); artworkGradient.invalidate()
        db.close(); super.onDestroy()
    }

    @Deprecated("Uses Android's intent sender result for system file deletion")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        batchActions.onActivityResult(requestCode, resultCode)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedIntents.receive(intent)
        sharedIntents.playPending()
    }
    private fun hasAccess(): Boolean = checkSelfPermission(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun requestAccess() = requestPermissions(arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE), 11)
    @Deprecated("Android framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11) { if (hasAccess()) { scan(); requestQueueRecovery() } else { scanned = false; render() } }
    }
    private fun requestQueueRecovery() {
        player?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.RETRY_QUEUE_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
    }
    private fun loadLibraryOnStart() = libraryLoader.loadOnStart()
    private fun scan(silent: Boolean = false, refreshArtwork: Boolean = false) = libraryLoader.scan(silent, refreshArtwork)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun pressable(color: Int, radius: Int = 16): android.graphics.drawable.Drawable {
        val fill = if (color == accent && !settings.useDeviceColors()) GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xfffff000.toInt(), 0xffffb000.toInt())
        ).apply { cornerRadius = dp(radius).toFloat() } else shape(color, radius)
        return android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(45, Color.red(onSurface), Color.green(onSurface), Color.blue(onSurface))), fill, null
        )
    }
    /** Apply the brand gradient to the existing outlined navigation symbol. */
    private fun gradientNavigationIcon(resource: Int): android.graphics.drawable.Drawable? = gradientIcon(resource, 48)
    /** Keep the player's on and off artwork at the same intrinsic 24dp size. */
    private fun gradientIcon(resource: Int, sizeDp: Int): android.graphics.drawable.Drawable? {
        val source = ContextCompat.getDrawable(this, resource)?.mutate() ?: return null
        val size = dp(sizeDp).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        source.setBounds(0, 0, size, size); source.setTint(Color.WHITE); source.draw(canvas)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                0xfffff000.toInt(), 0xffffa900.toInt(), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        bitmap.density = resources.displayMetrics.densityDpi
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }
    private fun label(text: String, size: Float, color: Int = onSurface, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        if (size >= 20f) letterSpacing = -0.015f
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(onSurface); background = pressable(surface, 18); PressFeedback.apply(this); setOnClickListener { action() }
    }
    private fun iconButton(icon: Int, description: String, fill: Int = surface, tint: Int = onSurface, action: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(ContextCompat.getDrawable(context, icon)); imageTintList = ColorStateList.valueOf(tint)
        background = pressable(fill, 24); setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = description
        PressFeedback.apply(this); setOnClickListener { action() }
    }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addWeighted(v: View, weight: Float = 1f) { addView(v, LinearLayout.LayoutParams(0, -2, weight)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun buildUi() {
        val shell = ActivityShell(object : ActivityShell.Host {
            override val activity get() = this@MainActivity
            override val bg get() = this@MainActivity.bg
            override val surface get() = this@MainActivity.surface
            override val onSurface get() = this@MainActivity.onSurface
            override val muted get() = this@MainActivity.muted
            override val accent get() = this@MainActivity.accent
            override val tileSurface get() = this@MainActivity.tileSurface
            override val onTile get() = this@MainActivity.onTile
            override val player get() = this@MainActivity.player
            override val tracks get() = this@MainActivity.tracks
            override var query: String
                get() = this@MainActivity.query
                set(value) { this@MainActivity.query = value }
            override fun dp(n: Int) = this@MainActivity.dp(n)
            override fun shape(color: Int, radius: Int) = this@MainActivity.shape(color, radius)
            override fun pressable(color: Int, radius: Int) = this@MainActivity.pressable(color, radius)
            override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
            override fun button(text: String, action: () -> Unit) = this@MainActivity.button(text, action)
            override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
            override fun col() = this@MainActivity.col()
            override fun row() = this@MainActivity.row()
            override fun showSidePanel() = this@MainActivity.showSidePanel()
            override fun openPlayer() = this@MainActivity.openPlayer()
            override fun togglePlayback() = this@MainActivity.togglePlayback()
            override fun rememberQuery(value: String) = searchPreferences.remember(value)
            override fun queryChanged() = searchCoordinator.onTextChanged()
            override fun selectionActions() = selection.showActions()
            override fun selectionDone() = selection.stop()
            override fun coverView(track: Track, size: Int) = this@MainActivity.coverView(track, size)
            override fun showQueue() = this@MainActivity.showQueue()
            override fun updatePagePadding() = this@MainActivity.updatePagePadding()
        })
        shell.build()
        screenHost = shell.screenHost
        rootLayout = shell.rootLayout
        topChrome = shell.topChrome
        statusScrim = shell.statusScrim
        bottomDock = shell.bottomDock
        topBar = shell.topBar
        homeBrandTitle = shell.homeBrandTitle
        searchHeading = shell.searchHeading
        searchInput = shell.searchInput
        searchContainer = shell.searchContainer
        searchClear = shell.searchClear
        searchCategoryTabs = shell.searchCategoryTabs
        searchCategoryScroll = shell.searchCategoryScroll
        libraryHeading = shell.libraryHeading
        tabs = shell.tabs
        tabScroll = shell.tabScroll
        libraryScroll = shell.libraryScroll
        content = shell.content
        listHost = shell.listHost
        queueHost = shell.queueHost
        miniPlayer = shell.miniPlayer
        miniArt = shell.miniArt
        miniTitle = shell.miniTitle
        miniArtist = shell.miniArtist
        miniPlay = shell.miniPlay
        miniNext = shell.miniNext
        miniProgress = shell.miniProgress
        selectionBar = shell.selectionBar
        selectionCount = shell.selectionCount
        queuePeek = shell.queuePeek
        bottomNav = shell.bottomNav
        bottomNavHost = shell.bottomNavHost
        bottomNavShell = shell.bottomNavShell
        setContentView(screenHost)
        TunelystLayout.observeInsets(screenHost) { left, top, right, bottom ->
            insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom
            applySafeInsets()
        }
    }
    /** Apply system insets independently from page margins and the floating dock. */
    private fun applySafeInsets() {
        if (!::rootLayout.isInitialized || !::bottomDock.isInitialized) return
        val rootTop = 0
        val rootBottom = insetBottom + dp(4)
        if (rootLayout.paddingLeft != insetLeft || rootLayout.paddingTop != rootTop || rootLayout.paddingRight != insetRight || rootLayout.paddingBottom != rootBottom)
            rootLayout.setPadding(insetLeft, rootTop, insetRight, rootBottom)
        if (bottomDock.paddingLeft != insetLeft || bottomDock.paddingRight != insetRight || bottomDock.paddingBottom != rootBottom)
            bottomDock.setPadding(insetLeft, 0, insetRight, rootBottom)
        if (topChrome.paddingLeft != insetLeft || topChrome.paddingTop != insetTop || topChrome.paddingRight != insetRight)
            topChrome.setPadding(insetLeft, insetTop, insetRight, 0)
        val statusHeight = if (insetTop > 0) insetTop + dp(12) else 0
        if (statusScrim.layoutParams.height != statusHeight) {
            statusScrim.layoutParams = statusScrim.layoutParams.apply { height = statusHeight }
        }
        updatePagePadding()
    }
    private fun updatePagePadding() {
        if (!::content.isInitialized || !::bottomDock.isInitialized) return
        val immersive = screen == "Player" || screen == "Queue"
        // The page remains full-height behind floating controls, but its last items
        // can scroll above them. The parent's clip bounds still protect the header.
        val estimatedDock = when {
            screen == "Player" -> dp(76)
            immersive -> 0
            else -> dp(64) + if (::miniPlayer.isInitialized && miniPlayer.visibility == View.VISIBLE) dp(76) else 0
        }
        val actualDock = (bottomDock.height - bottomDock.paddingBottom).coerceAtLeast(0)
        val clearance = if (screen == "Queue") 0 else maxOf(actualDock, estimatedDock)
        val hasChrome = groupIds == null && (screen == "Search" || screen == "Library")
        val chromeHeight = if (hasChrome) maxOf(topChrome.height, insetTop + dp(68)) else insetTop
        val top = dp(8) + chromeHeight
        val bottom = dp(12) + if (activeList == null) clearance else 0
        TunelystLayout.contentPadding(content, dp(16), top, bottom)
        updateCollectionHeroInsets()
        updateHomeHeroInsets()
        activeList?.let { list ->
            val listBottom = dp(12) + clearance
            if (list.paddingBottom != listBottom) list.setPadding(0, 0, 0, listBottom)
        }
    }
    private fun navigate(destination: String, libraryTab: String = "Songs") {
        artistReturnToPlayer = false
        screen = destination; playerReturnScreen = destination; settingsReturnScreen = destination; tab = libraryTab; groupTitle = null; groupIds = null; playlistId = null
        if (destination != "Search") { query = ""; if (searchInput.text.isNotEmpty()) searchInput.setText("") }
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        render(); libraryScroll.scrollTo(0, 0); animatePageEntrance()
        if (destination == "Library") revealSelectedLibraryTab()
        if (destination == "Home" || destination == "Search") refreshHomeHistory()
    }
    private fun revealSelectedLibraryTab() {
        tabScroll.post {
            if (screen != "Library" || groupIds != null) return@post
            val selected = (0 until tabs.childCount).map { tabs.getChildAt(it) }.firstOrNull { it.isSelected } ?: return@post
            val left = tabScroll.scrollX
            val right = left + tabScroll.width
            when {
                selected.left < left -> tabScroll.smoothScrollTo(selected.left, 0)
                selected.right > right -> tabScroll.smoothScrollTo(selected.right - tabScroll.width, 0)
            }
        }
    }
    private fun revealSelectedSearchCategory() {
        searchCategoryScroll.post {
            if (screen != "Search" || groupIds != null) return@post
            val selected = (0 until searchCategoryTabs.childCount).map { searchCategoryTabs.getChildAt(it) }.firstOrNull { it.isSelected } ?: return@post
            val left = searchCategoryScroll.scrollX
            val right = left + searchCategoryScroll.width
            when {
                selected.left < left -> searchCategoryScroll.smoothScrollTo(selected.left, 0)
                selected.right > right -> searchCategoryScroll.smoothScrollTo(selected.right - searchCategoryScroll.width, 0)
            }
        }
    }
    private fun openPlayer() {
        if (screen in listOf("Home", "Search", "Library", "Liked", "Settings")) playerReturnScreen = screen
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        screen = "Player"; render(); libraryScroll.scrollTo(0, 0); animatePageEntrance()
    }
    private fun openSettings() {
        if (screen in listOf("Home", "Search", "Library", "Liked")) settingsReturnScreen = screen
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        screen = "Settings"; render(); libraryScroll.scrollTo(0, 0)
    }
    private fun animatePageEntrance() {
        val target: View = if (listHost.visibility == View.VISIBLE) listHost else libraryScroll
        target.animate().cancel()
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { target.alpha = 1f; target.translationY = 0f; return }
        target.alpha = 0f; target.translationY = dp(8).toFloat()
        target.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }
    private fun togglePlayback() {
        player?.let { p ->
            if (p.isPlaying) p.pause() else if (p.mediaItemCount > 0) { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } else toast("Choose a song first")
        } ?: toast("Player connecting…")
    }
    private fun updateMiniPlayer() {
        if (!::miniPlayer.isInitialized) return
        val preview = PlaybackVisualState.current(player)
        val visible = screen in listOf("Home", "Search", "Library", "Liked", "Settings") && preview != null
        if (!visible) { miniPlayer.visibility = View.GONE; return }
        if (miniPlayer.visibility != View.VISIBLE) {
            miniPlayer.visibility = View.VISIBLE
            // Avoid a second entrance animation when reconnecting to an active service.
            if (player != null && !playbackConnecting && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                miniPlayer.alpha = 0f; miniPlayer.translationY = dp(8).toFloat()
                miniPlayer.animate().alpha(1f).translationY(0f).setDuration(200).start()
            } else { miniPlayer.alpha = 1f; miniPlayer.translationY = 0f }
        }
        val state = preview ?: return
        val id = state.mediaId
        val track = id.toLongOrNull()?.let { trackId -> currentTrackLookup.find(tracks, trackId) }
        val sharedArt = state.sharedArtwork
        val artKey = "$id:${track?.albumId}:${sharedArt?.contentHashCode()}"
        if (miniArtKey != artKey) {
            miniArtKey = artKey; miniArt.removeAllViews()
            miniArt.addView(if (track != null) coverView(track, dp(48)) else if (sharedArt != null) sharedArtworkView(sharedArt, dp(48)) else label("♫", 20f, onTile, true).apply { gravity = Gravity.CENTER; background = shape(surface, 10) }, FrameLayout.LayoutParams(-1, -1))
        }
        miniTitle.text = state.title ?: "Nothing playing"
        miniArtist.text = state.artist ?: "Unknown artist"
        miniPlay.setImageResource(if (state.playing) R.drawable.ny_pause else R.drawable.ny_play)
        miniPlay.contentDescription = if (state.playing) "Pause" else "Play"
        miniNext.isEnabled = state.canGoNext
        miniNext.alpha = if (miniNext.isEnabled) 1f else 0.4f
        val duration = state.duration; val position = state.positionNow()
        miniProgress.progress = if (duration > 0) ((position.coerceAtMost(duration) * 1000L) / duration).toInt() else 0
    }
    private fun chooseSleepTimer() = SleepTimerDialog.show(this, player) { toast(it) }
    private fun updatePlayer() {
        updateMiniPlayer(); updateSmartShuffleUi()
        if (screen != "Player" || !::playerTitle.isInitialized) return
        val p = player
        playerTitle.text = p?.mediaMetadata?.title ?: "Nothing playing"
        val meta = p?.mediaMetadata
        val shared = SharedAudio.isShared(p?.currentMediaItem)
        playerSub.text = if (shared) listOfNotNull(meta?.artist?.toString(), meta?.albumTitle?.toString()).joinToString(" · ").ifBlank { "Unknown artist" } else meta?.artist ?: "Choose a song from your library"
        val artist = p?.mediaMetadata?.artist?.toString()?.trim().orEmpty()
        val canOpenArtist = !shared && artist.isNotEmpty() && !artist.equals("Unknown artist", true)
        playerSub.isEnabled = canOpenArtist; playerSub.isClickable = canOpenArtist; playerSub.isFocusable = canOpenArtist
        playerSub.contentDescription = if (canOpenArtist) "Open artist $artist" else artist.ifBlank { "Choose a song from your library" }
        playButton.setImageResource(if (p?.isPlaying == true) R.drawable.ny_pause else R.drawable.ny_play)
        playButton.contentDescription = if (p?.isPlaying == true) "Pause" else "Play"
        repeatButton.setMode(p?.repeatMode ?: Player.REPEAT_MODE_OFF)
        val duration = p?.duration?.coerceAtLeast(0L) ?: 0L
        val position = p?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (!seeking) seek.progress = if (duration > 0) ((position.coerceAtMost(duration) * 1000L) / duration).toInt() else 0
        elapsed.text = format(position); total.text = format(duration)
    }
    private fun format(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    private fun rememberListPosition() {
        val list = activeList ?: return
        val key = activeListKey ?: return
        if (listRestorePending) return
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        listPositions.remove(key)
        listPositions[key] = first to top
        while (listPositions.size > 50) listPositions.remove(listPositions.keys.first())
    }
    private fun rememberScrollPosition() {
        val key = renderedScrollKey
        if (key != null && !scrollRestorePending && ::libraryScroll.isInitialized && libraryScroll.visibility == View.VISIBLE) scrollPositions[key] = libraryScroll.scrollY
        currentVibes?.let { homeVibesPage = it.currentPage() }
    }
    private fun scrollKey(): String = if (screen == "Home" && groupIds == null) "Home" else "$screen|$tab|$groupTitle|$playlistId"
    private fun setupContentHost() {
        rememberListPosition(); rememberScrollPosition(); currentVibes = null
        selection.clearIfScopeChanged()
        val nextScrollKey = scrollKey(); renderedScrollKey = nextScrollKey; scrollRestorePending = false
        // The Home header is reused between renders but must have only one parent.
        (topBar.parent as? android.view.ViewGroup)?.removeView(topBar)
        activeList = null; activeAdapter = null; lastListPlayback = null; activeListKey = null; listRestorePending = false; recentSongViews.clear(); collectionHeroController.reset(); homePage.reset()
        libraryScroll.removeAllViews(); listHost.removeAllViews()
        content = col().apply { setPadding(dp(16), dp(8), dp(16), dp(12)); clipChildren = false; clipToPadding = false }
        val listMode = hasAccess() && !loading && scanError == null && screen !in listOf("Player", "Settings", "Queue") &&
            (groupIds != null || screen == "Library" || screen == "Liked" || (screen == "Search" && query.isNotBlank()))
        libraryScroll.visibility = if (listMode || screen == "Queue") View.GONE else View.VISIBLE
        listHost.visibility = if (listMode) View.VISIBLE else View.GONE
        if (listMode) {
            val list = ListView(this).apply {
                divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = false; isFastScrollEnabled = false; clipToPadding = false; clipChildren = true
                setPadding(0, 0, 0, dp(12)); addHeaderView(content, null, false)
            }
            activeListKey = "$screen|$tab|$groupTitle|$playlistId|$query|$librarySort|${if (screen == "Search") searchFilter else ""}"
            activeList = list
            listRestorePending = activeListKey in listPositions
            listHost.addView(list, FrameLayout.LayoutParams(-1, -1))
            setLibraryRows(emptyList(), restorePosition = false)
        } else {
            libraryScroll.addView(content)
            scrollRestorePending = true
            libraryScroll.post { if (renderedScrollKey == nextScrollKey && libraryScroll.visibility == View.VISIBLE) { libraryScroll.scrollTo(0, scrollPositions[nextScrollKey] ?: 0); scrollRestorePending = false } }
        }
        updatePagePadding()
    }
    private fun setLibraryRows(rows: List<LibraryListAdapter.Row>, songs: List<Track> = emptyList(), restorePosition: Boolean = true) {
        val list = activeList ?: return
        val adapter = LibraryListAdapter(this, rows, onSurface, muted, accent, tileSurface, onTile,
            { it.id in favorites }, { track, size -> coverView(track, size) },
            { index -> if (index in songs.indices) playList(songs, index) },
            { toggleFavorite(it) }, { trackMenu(it) }, { listPlaybackState() }, { list, size -> collageView(list, size) }, { id -> queueStatuses[id] },
            { selection.ids }, { selection.toggle(it) })
        if (restorePosition) {
            selection.setShownSongs(if (rows is LibraryListAdapter.SongRows) rows.songs else rows.filterIsInstance<LibraryListAdapter.Row.Song>().map { it.track })
        }
        activeAdapter = adapter; lastListPlayback = listPlaybackState(); list.adapter = adapter
        val saved = activeListKey?.let { listPositions[it] }
        if (restorePosition && saved != null && rows.isNotEmpty()) {
            listRestorePending = true
            list.post {
                if (activeList === list && activeAdapter === adapter) {
                    list.setSelectionFromTop(saved.first.coerceIn(0, (list.count - 1).coerceAtLeast(0)), saved.second)
                    listRestorePending = false
                }
            }
        } else if (restorePosition && saved == null) listRestorePending = false
    }
    private fun refreshQueueStatuses() {
        val updated = QueuePriority.statuses(player)
        if (updated != queueStatuses) {
            queueStatuses = updated
            activeList?.let { activeAdapter?.refreshVisible(it) }
            updateRecentSongStates()
        }
    }
    private fun listPlaybackState(): Pair<Long?, Boolean> = player?.let { it.currentMediaItem?.mediaId?.toLongOrNull() to it.isPlaying } ?: (null to false)
    private fun updateListPlayingState() {
        val state = listPlaybackState()
        if (state != lastListPlayback) { lastListPlayback = state; activeList?.let { activeAdapter?.refreshVisible(it) } }
    }
    private fun refreshBottomNav() = bottomNavigation.render()
    private fun render() {
        if (!::content.isInitialized) return
        smartButton = null; homeSmartButton = null
        backCallback.isEnabled = selection.active || screen != "Home" || groupIds != null || homeSidePanel.isOpen
        setupContentHost()
        refreshBottomNav()
        val immersive = screen == "Player" || screen == "Queue"
        if (screen == "Home" && groupIds == null && (tracks.isEmpty() || !hasAccess() || loading || scanError != null)) {
            topBar.visibility = View.VISIBLE
            homeBrandTitle.setTextColor(onSurface)
            content.addView(topBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        bottomNavShell.visibility = if (immersive) View.GONE else View.VISIBLE
        queuePeek.visibility = if (screen == "Player") View.VISIBLE else View.GONE
        if (screen == "Player") queuePeek.refresh(player)
        updateMiniPlayer()
        applySafeInsets()
        queueHost.visibility = if (screen == "Queue") View.VISIBLE else View.GONE
        val searchVisible = screen == "Search" && groupIds == null
        val libraryVisible = screen == "Library" && groupIds == null
        searchHeading.visibility = if (searchVisible) View.VISIBLE else View.GONE
        searchInput.visibility = if (searchVisible) View.VISIBLE else View.GONE
        searchContainer.visibility = searchInput.visibility
        searchCategoryScroll.visibility = if (searchVisible && groupIds == null) View.VISIBLE else View.GONE
        libraryHeading.visibility = if (libraryVisible) View.VISIBLE else View.GONE
        tabScroll.visibility = if (libraryVisible) View.VISIBLE else View.GONE
        topChrome.visibility = if (searchVisible || libraryVisible) View.VISIBLE else View.GONE
        updatePagePadding()
        searchCategoryTabs.removeAllViews()
        SearchResultsLayout.categories.forEach { name ->
            val selected = searchFilter == name
            val b = button(name) {
                if (searchFilter != name) { searchFilter = name; render() }
                revealSelectedSearchCategory()
            }
            b.background = android.graphics.drawable.InsetDrawable(pressable(if (selected) accent else surface, 18), 0, dp(6), 0, dp(6))
            b.setTextColor(if (selected) onAccent else onSurface)
            b.isSelected = selected; b.contentDescription = if (selected) "$name, selected" else name
            b.textSize = 13f; b.maxLines = 1; b.setPadding(dp(14), 0, dp(14), 0)
            searchCategoryTabs.addView(b, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        }
        tabs.removeAllViews()
        listOf("Songs", "Albums", "Artists", "Folders", "Playlists").forEach { name ->
            val b = button(name) {
                tab = name; groupTitle = null; groupIds = null; playlistId = null
                render(); libraryScroll.scrollTo(0, 0); revealSelectedLibraryTab()
            }
            // The visible pill is 36dp tall within a 48dp touch target.
            b.background = android.graphics.drawable.InsetDrawable(pressable(if (tab == name) accent else surface, 18), 0, dp(6), 0, dp(6))
            b.setTextColor(if (tab == name) onAccent else onSurface)
            b.isSelected = tab == name; b.contentDescription = if (tab == name) "$name, selected" else name
            b.textSize = 13f; b.maxLines = 1; b.setPadding(dp(14), 0, dp(14), 0)
            tabs.addView(b, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        }
        queueHost.removeAllViews()
        if (screen == "Queue") { screen = "Player"; renderPlayer(); return }
        if (screen == "Settings") { renderSettings(); return }
        if (screen == "Player") { renderPlayer(); return }
        if (!hasAccess()) {
            stateCard("Your music belongs here", "Allow access to audio on this device to build your offline library. Tunelyst does not stream music.")
            content.addView(button("Allow audio access") { requestAccess() })
            content.addView(button("Open app settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
            return
        }
        if (loading) {
            if (libraryLoader.isCacheRestorePending) stateCard("Opening your library", "Restoring your saved library…", true)
            else stateCard("Finding your music", "Scanning audio stored on this device…", true)
            return
        }
        if (scanError != null) {
            stateCard("Couldn't scan your library", scanError!!)
            content.addView(button("Try again") { scan() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return
        }
        if (libraryLoader.isCacheRestorePending && groupIds == null &&
            (screen == "Library" || (screen == "Search" && query.isNotBlank()))) {
            stateCard("Preparing your library", "Getting songs ready…", true)
            return
        }
        if (groupIds != null) { collectionPage.render(); return }
        when {
            screen == "Home" -> renderHome()
            screen == "Liked" -> {
                val list = tracks.filter { it.id in favorites }
                addCollectionHero("Liked songs", list) { navigate("Home") }
                if (list.isEmpty()) stateCard("No liked songs yet", "Tap the heart next to a song to save it here.")
                else {
                    addTracks(list)
                }
            }
            screen == "Search" -> searchPage.render()
            else -> libraryPage.render()
        }
    }
    private fun songHistoryRow(track: Track, list: List<Track>, index: Int, contextLabel: String): View {
        val item = row().apply {
            minimumHeight = dp(64); setPadding(dp(8), dp(6), dp(6), dp(6)); background = pressable(surface, 18)
            isClickable = true; isFocusable = true
            TrackTouchFeedback.apply(this)
            contentDescription = "Play $contextLabel ${track.title}, ${track.artist}, ${track.album}. Touch and hold for song options."
            setOnClickListener { playList(list, index) }
            setOnLongClickListener { trackMenu(track); true }
        }
        item.addView(coverView(track, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val texts = col()
        val title = label(track.title, 15f, onSurface, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(title)
        texts.addView(label("${track.artist} · ${format(track.duration)}", 12f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        item.addWeighted(texts)
        val icons = QueueStatusIcons(this, accent)
        item.addView(icons, LinearLayout.LayoutParams(-2, dp(24)))
        val playing = PlayingIndicator(this, accent)
        item.addView(playing, LinearLayout.LayoutParams(dp(20), dp(24)))
        item.addView(iconButton(R.drawable.ny_heart, if (track.id in favorites) "Unlike ${track.title}" else "Like ${track.title}", surface, if (track.id in favorites) accent else muted) {
            toggleFavorite(track)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        item.addView(iconButton(R.drawable.ny_more, "Options for ${track.title}", surface, onSurface) { trackMenu(track) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        recentSongViews.add(RecentSongViews(track, item, title, icons, playing, contextLabel))
        updateRecentSongStates()
        return item
    }
    private fun updateRecentSongStates() {
        val (id, isPlaying) = listPlaybackState()
        recentSongViews.forEach { item ->
            val current = item.track.id == id
            val status = queueStatuses[item.track.id]
            item.title.setTextColor(if (current) accent else onSurface)
            item.playing.setState(current, current && isPlaying)
            if (!current) item.playing.visibility = View.GONE
            item.icons.setStatus(status)
            item.root.contentDescription = "Play ${item.contextLabel} ${item.track.title}, ${item.track.artist}, ${item.track.album}${if (current) if (isPlaying) ", now playing" else ", paused" else ""}${status?.let { ", $it" } ?: ""}. Touch and hold or use song options."
        }
    }
    private fun renderRecentlySearchedSongs() {
        val byId = tracks.associateBy { it.id }
        val songs = libraryUiPreferences.searchedSongIds().mapNotNull { byId[it] }
        val heading = row().apply { setPadding(0, dp(4), 0, dp(10)) }
        heading.addWeighted(label("Recents", 20f, onSurface, true))
        if (songs.isNotEmpty()) heading.addView(button("Clear") {
            libraryUiPreferences.clearSearchedSongs(); render()
        }.apply { contentDescription = "Clear recently searched songs" }, LinearLayout.LayoutParams(-2, -2))
        content.addView(heading)
        if (songs.isEmpty()) {
            content.addView(label("Songs you choose from Search will appear here.", 13f, muted).apply { setPadding(0, dp(8), 0, dp(16)) })
        } else songs.forEachIndexed { index, track ->
            content.addView(songHistoryRow(track, songs, index, "recently searched"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
    }
    private fun sortedLibraryTracks(): List<Track> = libraryIndex.sorted(librarySort)
    private fun renderPlayer() {
        renderedPlayerId = player?.currentMediaItem?.mediaId
        renderedSharedArtKey = if (SharedAudio.isShared(player?.currentMediaItem)) player?.mediaMetadata?.artworkData?.contentHashCode() else null
        val views = NowPlayingPage.render(object : NowPlayingPage.Host {
            override val context get() = this@MainActivity
            override val content get() = this@MainActivity.content
            override val player get() = this@MainActivity.player
            override val tracks get() = this@MainActivity.tracks
            override val favorites get() = this@MainActivity.favorites
            override val bg get() = this@MainActivity.bg
            override val surface get() = this@MainActivity.surface
            override val onSurface get() = this@MainActivity.onSurface
            override val accent get() = this@MainActivity.accent
            override val muted get() = this@MainActivity.muted
            override val onTile get() = this@MainActivity.onTile
            override val tileSurface get() = this@MainActivity.tileSurface
            override fun dp(value: Int) = this@MainActivity.dp(value)
            override fun row() = this@MainActivity.row()
            override fun col() = this@MainActivity.col()
            override fun label(text: String, size: Float, color: Int, bold: Boolean) = this@MainActivity.label(text, size, color, bold)
            override fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit) = this@MainActivity.iconButton(icon, description, fill, tint, action)
            override fun shape(color: Int, radius: Int) = this@MainActivity.shape(color, radius)
            override fun pressable(color: Int, radius: Int) = this@MainActivity.pressable(color, radius)
            override fun cover(track: Track, size: Int) = coverView(track, size)
            override fun sharedCover(data: ByteArray, size: Int) = sharedArtworkView(data, size)
            override fun applyGradient(track: Track?, target: View) { artworkGradient.apply(track, target, overlay = false, accent = accent) }
            override fun back() { screen = playerReturnScreen; render(); if (screen == "Home") refreshHomeHistory() }
            override fun showMenu() = showPlayerMenu()
            override fun openArtist() = openPlayingArtist()
            override fun toggleFavorite(track: Track?) { if (track != null) this@MainActivity.toggleFavorite(track) else toast("Choose a song first") }
            override fun seekStarted() { seeking = true }
            override fun seekStopped(progress: Int) { val pl = player; if (pl != null && pl.duration > 0) pl.seekTo(pl.duration * progress / 1000); seeking = false; updatePlayer() }
            override fun toggleSmartShuffle() = this@MainActivity.toggleSmartShuffle()
            override fun toggleRepeat() { player?.let { it.repeatMode = RepeatControl.next(it.repeatMode); updatePlayer() } ?: toast("Player connecting…") }
            override fun togglePlayback() = this@MainActivity.togglePlayback()
            override fun previous() { player?.seekToPreviousMediaItem() }
            override fun next() { player?.seekToNextMediaItem() }
        })
        playerTitle = views.title; playerSub = views.subtitle; playButton = views.play; playerMenuButton = views.menu
        repeatButton = views.repeat; smartButton = views.smart; seek = views.seek; elapsed = views.elapsed; total = views.total
        updatePlayer()
    }
    private fun showPlayerMenu() {
        if (!::playerMenuButton.isInitialized) return
        android.widget.PopupMenu(this, playerMenuButton).apply {
            menu.add(0, 1, 0, "Playback speed · ${settings.speed()}×")
            menu.add(0, 2, 1, "Sleep timer")
            if (SharedAudio.isShared(player?.currentMediaItem)) menu.add(0, 3, 2, "Song details")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { settings.chooseSpeed { speed -> player?.setPlaybackSpeed(speed); render() }; true }
                    2 -> { chooseSleepTimer(); true }
                    3 -> { player?.let { SongDetails.showShared(this@MainActivity, it.mediaMetadata, it.duration) }; true }
                    else -> false
                }
            }
            show()
        }
    }
    private fun closeSidePanel() = homeSidePanel.close()
    private fun showSidePanel() { if (::screenHost.isInitialized) homeSidePanel.show() }
    private fun renderSettings() = settingsPage.render()
    private fun refreshHomeHistory() {
        val request = ++historyRequest
        io.execute {
            val result = runCatching { LibraryDb(applicationContext).use { it.listeningStats() } }
            main.post {
                if (!isDestroyed && request == historyRequest) {
                    val wasReady = homeHistoryReady
                    val wasFailed = homeHistoryFailed
                    homeHistoryFailed = result.isFailure
                    if (result.isSuccess) {
                        val updated = result.getOrThrow()
                        val changed = updated != history
                        history = updated; homeHistoryReady = true
                        if ((changed || !wasReady || wasFailed) && !loading && groupIds == null && screen == "Home") homePage.refreshContent()
                    } else if (!wasReady && !loading && groupIds == null && screen == "Home") homePage.refreshContent()
                }
            }
        }
    }
    private fun refreshPendingHomeArtwork() {
        if (!pendingHomeArtworkRefresh) return
        pendingHomeArtworkRefresh = false
        homePage.refreshHeroArtwork()
        val id = PlaybackVisualState.current(player)?.mediaId?.toLongOrNull()
        val track = id?.let { current -> currentTrackLookup.find(tracks, current) }
        if (track != null && ::miniArt.isInitialized && miniArt.childCount > 0)
            artworkViews.refreshCover(track, miniArt.getChildAt(0), dp(48))
    }
    private fun renderHome() = homePage.render()
    private fun updateHomeHeroInsets() = homePage.updateHomeHeroInsets()
    private fun updateHomeHero() = homePage.updateHomeHero()
    private fun updateVibePlayingState() = homePage.updateVibePlayingState()

    private fun collageView(list: List<Track>, size: Int): View = artworkViews.collage(list, size)
    private fun sharedArtworkView(data: ByteArray, size: Int): View {
        val frame = FrameLayout(this).apply { background = shape(tileSurface, if (size <= dp(64)) 10 else 24); clipToOutline = true }
        frame.addView(label("♫", if (size <= dp(64)) 20f else 56f, onTile, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Shared song artwork" }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        io.execute {
            val bitmap = runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
            main.post { if (!isDestroyed && bitmap != null) image.setImageBitmap(bitmap) }
        }
        return frame
    }
    private fun coverView(track: Track, size: Int): View = artworkViews.cover(track, size)
    private fun matches(t: Track) = query.isBlank() || t.title.contains(query, true) || t.artist.contains(query, true) || t.album.contains(query, true)
    private fun header(title: String, detail: String) {
        content.addView(label("YOUR OFFLINE COLLECTION", 10f, accent, true).apply { letterSpacing = 0.12f; setPadding(0, dp(10), 0, dp(4)) })
        content.addView(label(title, 27f, onSurface, true).apply { setPadding(0, 0, 0, dp(3)) })
        content.addView(label(detail, 12f, muted).apply { setPadding(0, 0, 0, dp(14)) })
    }
    private fun artworkShape(key: String): GradientDrawable = PlaceholderArtwork.gradient(key, dp(12).toFloat())
    private fun stateCard(title: String, detail: String, busy: Boolean = false) {
        val box = col().apply {
            background = shape(surface, 24).apply { setStroke(dp(1).coerceAtLeast(1), 0x1affffff) }; setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(onTile)
            background = shape(tileSurface, 16); setPadding(dp(13), dp(13), dp(13), dp(13))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(14) })
        box.addView(label(title, 21f, onSurface, true))
        box.addView(label(detail, 14f, muted).apply { setPadding(0, dp(8), 0, 0) })
        if (busy) box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent)
            contentDescription = "Scanning music"
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(18) })
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
    }
    private fun empty() {
        val (title, detail) = when {
            screen == "Search" && query.isNotBlank() -> "No matches found" to "Try a different song title, artist or album."
            playlistId != null -> "Your playlist is empty" to "Tap Add songs above to choose music for this playlist."
            tracks.isEmpty() -> "No music found yet" to "Add audio files to your device, then refresh your library in Settings."
            screen == "Library" && tab == "Folders" && groupIds == null -> "No folders found" to "Refresh your library after adding audio files."
            else -> "Nothing here yet" to "There are no tracks in this collection yet."
        }
        stateCard(title, detail)
    }
    private fun collectionRow(title: String, detail: String, click: () -> Unit, hold: (() -> Unit)? = null, coverTrack: Track? = null, iconRes: Int? = null) {
        val item = row().apply { minimumHeight = dp(72); setPadding(dp(12), dp(12), dp(12), dp(12)); background = pressable(surface, 18) }
        val tile: View = when {
            iconRes != null -> ImageView(this).apply {
                setImageResource(iconRes); imageTintList = ColorStateList.valueOf(onTile)
                background = shape(tileSurface, 12); setPadding(dp(12), dp(12), dp(12), dp(12))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            coverTrack != null -> coverView(coverTrack, dp(48))
            else -> label(title.take(1).uppercase(), 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = artworkShape(title) }
        }
        item.addView(tile, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val texts = col(); texts.addView(label(title, 15f, onSurface, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }); texts.addView(label(detail, 13f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
        item.addWeighted(texts)
        item.addView(label("›", 24f, muted))
        item.setOnClickListener { click() }; if (hold != null) item.setOnLongClickListener { hold(); true }
        content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }
    private fun closeCollection() {
        val returnToPlayer = artistReturnToPlayer
        artistReturnToPlayer = false; groupTitle = null; groupIds = null; playlistId = null
        if (returnToPlayer) screen = "Player"
        render()
    }
    private fun openPlayingArtist() {
        val artist = player?.mediaMetadata?.artist?.toString()?.trim().orEmpty()
        if (artist.isBlank() || artist.equals("Unknown artist", true)) return
        val songs = tracks.filter { it.artist.trim().equals(artist, true) }
        if (songs.isEmpty()) { toast("Artist not found in your library"); return }
        screen = "Library"; tab = "Artists"; artistReturnToPlayer = true
        openGroup(artist, songs)
    }
    private fun openGroup(title: String, list: List<Track>, source: View? = null) {
        if (screen == "Search") {
            searchInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
        val transition = CollectionTransition(this, main)
        transition.capture(source)
        playlistId = null; groupTitle = title; groupIds = list.map { it.id }; collectionHeroController.reset(); render(); animatePageEntrance()
        transition.animate(collectionHeroController.view) { groupTitle == title && screen != "Player" }
    }
    private fun addCollectionHero(title: String, list: List<Track>, onBack: () -> Unit = { closeCollection() }) =
        collectionHeroController.render(title, list, onBack)
    private fun updateCollectionPlayButton() = collectionHeroController.updateButton()
    private fun updateCollectionHeroInsets() = collectionHeroController.updateInsets()
    private fun addPlayAll(list: List<Track>, includePlay: Boolean = true) {
        if (list.isEmpty()) return
        val actions = row()
        if (includePlay) actions.addView(button(if (screen == "Search") "▶  Play results" else "▶  Play") { playList(list, 0, searchSelection = false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        actions.addView(button("✦  Smart shuffle") { startSmartShuffle(list) }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun addTracks(list: List<Track>) {
        if (list.isEmpty()) { empty(); return }
        setLibraryRows(LibraryListAdapter.SongRows(list), list)
    }
    private fun playList(list: List<Track>, index: Int, searchSelection: Boolean = true, sourceOverride: String? = null, openPlayerOnStart: Boolean = true) {
        if (screen == "Search" && groupIds == null && searchSelection && index in list.indices) searchPreferences.remember(query)
        playbackStarter.playList(list, index, searchSelection, sourceOverride, openPlayerOnStart)
    }
    private fun startSmartShuffle(list: List<Track>) = playbackStarter.startSmartShuffle(list)
    private fun updateSmartShuffleUi() {
        val enabled = PlaybackVisualState.current(player)?.shuffle == true
        smartButton?.let { button ->
            if (button.isSelected != enabled) {
                if (enabled && !settings.useDeviceColors()) {
                    button.imageTintList = null
                    button.setImageDrawable(gradientIcon(R.drawable.ny_smart_shuffle, 24))
                } else {
                    button.setImageResource(R.drawable.ny_smart_shuffle)
                    button.imageTintList = ColorStateList.valueOf(if (enabled) accent else Color.WHITE)
                }
                button.isSelected = enabled
            }
            button.contentDescription = "Smart Shuffle ${if (enabled) "on" else "off"}. Tap to ${if (enabled) "turn off" else "turn on"}."
        }
        homeSmartButton?.let { button ->
            val nextText = if (enabled) "Smart shuffle · on" else "Smart shuffle"
            if (button.isSelected != enabled || button.text.toString() != nextText) {
                button.text = nextText
                val icon = if (enabled && !settings.useDeviceColors()) gradientNavigationIcon(R.drawable.ny_smart_shuffle)
                    else ContextCompat.getDrawable(this, R.drawable.ny_smart_shuffle)?.mutate()
                icon?.setBounds(0, 0, dp(22), dp(22))
                button.setCompoundDrawablesRelative(icon, null, null, null)
                button.compoundDrawableTintList = if (enabled && !settings.useDeviceColors()) null
                    else ColorStateList.valueOf(if (enabled) accent else Color.WHITE)
                button.setTextColor(if (enabled && settings.useDeviceColors()) accent else Color.WHITE)
                button.paint.shader = if (enabled && !settings.useDeviceColors()) android.graphics.LinearGradient(
                    0f, 0f, button.paint.measureText(nextText).coerceAtLeast(1f), 0f,
                    0xfffff000.toInt(), 0xffffa900.toInt(), android.graphics.Shader.TileMode.CLAMP
                ) else null
                button.invalidate()
                button.isSelected = enabled
            }
            button.contentDescription = "Smart Shuffle ${if (enabled) "on" else "off"}. Tap to ${if (enabled) "turn off" else "turn on"}."
        }
    }
    private fun toggleSmartShuffle() {
        val p = player ?: run { toast("Player connecting, try again"); return }
        if (p.currentMediaItem == null) { toast("Choose a song first"); return }
        sendSmartCommand(if (p.shuffleModeEnabled) "off" else "on", announce = true)
    }
    private fun sendSmartCommand(mode: String, announce: Boolean) {
        val p = player ?: return
        val args = Bundle().apply { putString(PlaybackService.SMART_SHUFFLE_MODE, mode) }
        val future = p.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.SMART_SHUFFLE_COMMAND, Bundle.EMPTY), args)
        future.addListener({
            if (!isDestroyed && player === p) {
                val ok = runCatching { future.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                if (!ok) toast("Couldn't change Smart Shuffle; try again")
                else if (announce) toast(if (mode == "off") "Smart Shuffle off" else "Smart Shuffle on")
                updateSmartShuffleUi()
            }
        }, mainExecutor)
    }
    private fun playlistEditor(id: Long) = PlaylistEditor(this, io) {
        if (playlistId == id) {
            groupIds = db.trackIds(id)
            groupTitle = db.playlists().firstOrNull { it.first == id }?.second ?: groupTitle
        }
        render()
    }
    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this).setTitle("Clear listening history?")
            .setMessage("This clears the local play and skip counts used by Smart Shuffle. Your liked songs and playlists stay unchanged. The current song can be counted again while playing.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                io.execute {
                    val result = runCatching { LibraryDb(applicationContext).use { it.clearListeningStats() } }
                    main.post { if (!isDestroyed) { toast(if (result.isSuccess) "Listening history cleared" else "Couldn't clear listening history"); if (result.isSuccess) refreshHomeHistory() } }
                }
            }.show()
    }
    private fun toggleFavorite(t: Track) {
        val adding = t.id !in favorites
        db.setFavorite(t.id, adding); favorites = db.favorites()
        toast(if (adding) "Added to liked songs" else "Removed from liked songs")
        if (groupTitle == "Liked songs" && playlistId == null) groupIds = tracks.filter { it.id in favorites }.map { it.id }
        render()
    }
    private fun vibeTrackMenu(track: Track) {
        MaterialAlertDialogBuilder(this).setTitle(track.title)
            .setItems(arrayOf("Hide from Instant Vibes", "Song options")) { _, which ->
                when (which) {
                    0 -> { val hidden = vibePrefs.getStringSet("hidden", emptySet())?.toMutableSet() ?: mutableSetOf(); hidden.add(track.id.toString()); vibePrefs.edit().putStringSet("hidden", hidden).apply(); toast("Hidden from Instant Vibes"); render() }
                    else -> trackMenu(track)
                }
            }.show()
    }
    private fun trackMenu(t: Track) = songActions.menu(t)
    private fun currentSelectionScope(): String = "$screen|$tab|$groupTitle|$playlistId|$query|$searchFilter"
    private fun addToPlaybackQueue(track: Track, playNext: Boolean) = songActions.addToQueue(track, playNext)
    private fun createPlaylist(after: ((Long) -> Unit)? = null) = songActions.createPlaylist(after)
    private fun choosePlaylist(t: Track) = songActions.choosePlaylist(t)
    private fun addToPlaylist(id: Long, t: Track) = songActions.addToPlaylist(id, t)
    private fun confirmDelete(id: Long, name: String) = songActions.confirmDelete(id, name)
    private fun showQueue() = queueController.show()

}
