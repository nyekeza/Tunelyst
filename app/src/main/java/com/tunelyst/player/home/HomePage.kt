package com.tunelyst.player

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player

internal interface HomePageHost {
        val context: Context
        val content: LinearLayout
        val topBar: LinearLayout
        val homeBrandTitle: TextView
        val insetTop: Int
        val tracks: List<Track>
        fun findTrack(id: Long): Track?
        val favorites: Set<Long>
        val history: Map<Long, TrackStats>
        val homeHistoryReady: Boolean
        val homeHistoryFailed: Boolean
        val homeContentCache: HomeContentCache
        val vibePrefs: SharedPreferences
        var homeVibesPage: Int
        var currentVibes: VibesCarousel?
        var homeSmartButton: Button?
        val player: Player?
        val playbackConnecting: Boolean
        val uiStarted: Boolean
        fun queueStatus(id: Long): String? = null
        val screen: String
        val groupIds: List<Long>?
        val loading: Boolean
        val isDestroyed: Boolean
        val bg: Int
        val surface: Int
        val onSurface: Int
        val tileSurface: Int
        val onAccent: Int
        val accent: Int
        val muted: Int
        fun dp(value: Int): Int
        fun row(): LinearLayout
        fun col(): LinearLayout
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun button(text: String, action: () -> Unit): Button
        fun shape(color: Int, radius: Int): Drawable
        fun pressable(color: Int, radius: Int): Drawable
        fun coverView(track: Track, size: Int): View
        fun refreshCoverView(track: Track, view: View, size: Int)
        fun sharedArtworkView(data: ByteArray, size: Int): View
        fun collageView(tracks: List<Track>, size: Int): View
        fun playList(tracks: List<Track>, index: Int, sourceOverride: String? = null)
        fun openGroup(title: String, tracks: List<Track>, source: View)
        fun vibeTrackMenu(track: Track)
        fun startSmartShuffle(tracks: List<Track>)
        fun updateSmartShuffleUi()
        fun openPlayer()
        fun toast(message: String)
        fun header(title: String, detail: String)
        fun empty()
        fun requestRender()
    }
/** Home's view construction and view state; playback and navigation remain with the host. */
internal class HomePage(private val host: HomePageHost) : HomePageHost by host {
    private data class VibePlayingView(val id: Long, val title: TextView, val status: PlayingIndicator, val queueIcons: QueueStatusIcons, val root: View, val songTitle: String, val artist: String)
    private val vibePlayingViews = mutableListOf<VibePlayingView>()
    private var heroArtKey: String? = null
    private var homeHero: FrameLayout? = null
    private var homeBody: LinearLayout? = null
    private var bodyRefreshPending = false
    private lateinit var heroArt: FrameLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroArtist: TextView
    private lateinit var heroEyebrow: TextView
    private lateinit var heroPlay: ImageButton
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }
    fun reset() { homeHero = null; heroArtKey = null; homeBody = null; bodyRefreshPending = false; vibePlayingViews.clear() }
    fun refreshContent() {
        if (!uiStarted) { bodyRefreshPending = true; return }
        renderBody()
    }
    fun onStart() { if (bodyRefreshPending) { bodyRefreshPending = false; renderBody() } }
    fun canRefreshContent() = homeBody != null && tracks.isNotEmpty()
    fun refreshHeroArtwork() {
        if (screen != "Home" || groupIds != null || !::heroArt.isInitialized || heroArt.childCount == 0) return
        val preview = PlaybackVisualState.current(player)
        val track = when {
            preview?.shared == true -> null
            preview != null -> preview.mediaId.toLongOrNull()?.let(::findTrack)
            else -> null
        }
        if (track != null) refreshCoverView(track, heroArt.getChildAt(0), dp(360))
    }
    /** Artwork-led Home screen, built only from local files and recorded plays. */
    fun render() {
        if (tracks.isEmpty()) {
            header("Welcome to Tunelyst", "Your offline music, all in one place")
            empty(); return
        }
        addHomeHero()
        val quick = row().apply { setPadding(0, dp(16), 0, 0) }
        val homeButton = button("Smart shuffle") { startSmartShuffle(tracks) }.apply {
            val icon = ContextCompat.getDrawable(context, R.drawable.ny_smart_shuffle)?.mutate()
            icon?.setBounds(0, 0, dp(22), dp(22)); setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE); compoundDrawablePadding = dp(8)
            setTextColor(Color.WHITE)
        }
        homeSmartButton = homeButton; quick.addView(homeButton, LinearLayout.LayoutParams(-2, -2)); updateSmartShuffleUi()
        content.addView(quick)
        homeBody = col().also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        renderBody()
    }
    private fun renderBody() {
        val body = homeBody ?: return
        if (screen != "Home" || groupIds != null || tracks.isEmpty()) return
        currentVibes?.let { homeVibesPage = it.currentPage() }
        currentVibes = null; vibePlayingViews.clear(); body.removeAllViews()

        // Remember the last displayed picks across days and explicit refreshes.
        val today = java.time.LocalDate.now().toString()
        if (vibePrefs.getString("day", null) != today) {
            val lastShown = vibePrefs.getStringSet("last_shown", emptySet())?.toSet() ?: emptySet()
            vibePrefs.edit().putString("day", today).putStringSet("previous", lastShown).apply()
        }
        val refresh = vibePrefs.getInt("refresh", 0)
        val avoid = vibePrefs.getStringSet("previous", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        val hiddenVibes = vibePrefs.getStringSet("hidden", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        val saved = HomeVibesSnapshot.read(vibePrefs, today, refresh, tracks, hiddenVibes)
        val readyHome = if (homeHistoryReady) homeContentCache.get(tracks, favorites, history, refresh, avoid, hiddenVibes) {
            if (!isDestroyed && screen == "Home" && groupIds == null && !loading) refreshContent()
        } else homeContentCache.previous(tracks, favorites, refresh, avoid, hiddenVibes)
        // Reopening never substitutes the first library songs for the actual mix.
        val home = when {
            readyHome != null -> HomeVibesSnapshot.stabilize(saved, readyHome, hiddenVibes)
            saved != null -> HomeContent(saved.vibes, saved.hasHistory, emptyList(), emptyList())
            else -> HomeContent(emptyList(), false, emptyList(), emptyList())
        }
        if (readyHome != null) {
            HomeVibesSnapshot.write(vibePrefs, today, refresh, home)
            val shown = home.vibes.map { it.id.toString() }.toSet()
            if (shown != vibePrefs.getStringSet("last_shown", emptySet())) vibePrefs.edit().putStringSet("last_shown", shown).apply()
        }
        val heading = row().apply { setPadding(0, dp(24), 0, dp(12)) }
        val headingText = col()
        headingText.addView(label("Instant Vibes", 22f, onSurface, true).apply { setTypeface(null, Typeface.BOLD) })
        headingText.addView(label(if (home.hasPlayHistory) "Your most played and more" else "Selections from your library", 13f, muted).apply { setPadding(0, dp(2), 0, 0) })
        heading.addWeighted(headingText)
        val refreshControl = iconButton(R.drawable.ny_refresh, "Refresh Instant Vibes", surface, onSurface) {
            vibePrefs.edit().putInt("refresh", refresh + 1)
                .putStringSet("previous", home.vibes.map { it.id.toString() }.toSet()).apply()
            currentVibes?.scrollTo(0, 0); homeVibesPage = 0; refreshContent()
        }.apply {
            isEnabled = home.vibes.isNotEmpty() || readyHome != null
            alpha = if (isEnabled) 1f else 0.38f
        }
        heading.addView(refreshControl, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        val playAllControl = button("Play all") {
            playList(home.vibes, 0, sourceOverride = "Instant Vibes")
        }.apply {
            contentDescription = "Play all Instant Vibes"
            setTextColor(onSurface); background = pressable(bg, 18)
            isEnabled = home.vibes.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.38f
        }
        heading.addView(playAllControl, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4) })
        body.addView(heading)
        val pages = row().apply { gravity = Gravity.TOP }
        home.vibes.chunked(4).forEachIndexed { pageIndex, songs ->
            val page = col().apply { contentDescription = "Instant Vibes page ${pageIndex + 1}" }
            songs.forEachIndexed { rowIndex, track -> addVibeRow(page, track, home.vibes, pageIndex * 4 + rowIndex) }
            pages.addView(page, LinearLayout.LayoutParams(0, -2))
        }
        if (home.vibes.isEmpty() && readyHome != null) body.addView(label("No songs in Instant Vibes. Restore hidden songs in Settings.", 13f, muted))
        val vibeCarousel = VibesCarousel(context, pages)
        currentVibes = vibeCarousel; vibeCarousel.restorePage(homeVibesPage)
        body.addView(vibeCarousel, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -dp(16); rightMargin = -dp(16) })
        updateVibePlayingState()

        if (readyHome == null) {
            body.addView(label(if (homeHistoryFailed) "Couldn't load listening history. Reopen Tunelyst to retry." else if (homeContentCache.hasFailure) "Couldn't prepare mixes. Reopen Tunelyst to retry." else "Preparing your mixes…", 13f, muted).apply { setPadding(0, dp(18), 0, dp(12)) })
            return
        }
        homeSection("Made for you", if (favorites.isEmpty()) "Like songs to shape your mix" else "Based on your likes and artists")
        homeCarousel { container -> home.mixes.forEach { mix -> homeCard(container, mix.title, mix.detail, mix.tracks) } }
        if (home.artists.isNotEmpty()) {
            homeSection("Best of your artists", if (home.hasPlayHistory) "Artists ranked by your local plays" else "Start listening to personalize this")
            homeCarousel { container -> home.artists.forEach { artist ->
                val detail = if (artist.playCount > 0) "${artist.playCount} ${if (artist.playCount == 1L) "play" else "plays"} · ${artist.tracks.size} songs" else "${artist.tracks.size} local songs"
                homeCard(container, artist.name, detail, artist.tracks)
            } }
        }
    }
    private fun addHomeHero() {
        val hero = FrameLayout(context).apply {
            background = shape(tileSurface, 0); clipToOutline = true
            contentDescription = "Open now playing"; isClickable = true; isFocusable = true
            setOnClickListener { openPlayer() }
        }
        homeHero = hero
        heroArt = FrameLayout(context)
        hero.addView(heroArt, FrameLayout.LayoutParams(-1, -1))
        val scrim = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xb0000000.toInt(), 0x22000000, 0xe8000000.toInt()))
        }
        hero.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        (topBar.parent as? android.view.ViewGroup)?.removeView(topBar)
        homeBrandTitle.setTextColor(Color.WHITE)
        hero.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), insetTop + dp(4), dp(16), 0) })
        val info = col()
        heroEyebrow = label("YOUR MUSIC", 10f, Color.WHITE, true).apply { letterSpacing = 0.14f }
        heroTitle = label("Ready when you are", 26f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        heroArtist = label("Your offline collection", 14f, 0xffdddddd.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(heroEyebrow); info.addView(heroTitle); info.addView(heroArtist)
        hero.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(20), 0, dp(80), dp(22)) })
        heroPlay = iconButton(R.drawable.ny_play, "Play or pause", accent, onAccent) {
            val p = player
            if (p == null) toast("Player connecting…")
            else if (p.isPlaying) p.pause()
            else if (p.mediaItemCount > 0) { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            else if (tracks.isNotEmpty()) playList(tracks, 0)
        }.apply { background = pressable(accent, 24); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        hero.addView(heroPlay, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(20)) })
        content.addView(hero, LinearLayout.LayoutParams(-1, ImmersiveHeroSize.height(dp(ImmersiveHeroSize.BASE_DP), content.paddingTop)).apply {
            leftMargin = -dp(16); rightMargin = -dp(16); topMargin = -content.paddingTop
        })
        heroArtKey = null
        updateHomeHero()
    }
    fun updateHomeHeroInsets() {
        val hero = homeHero ?: return
        val params = hero.layoutParams as? LinearLayout.LayoutParams ?: return
        val top = -content.paddingTop; val height = ImmersiveHeroSize.height(dp(ImmersiveHeroSize.BASE_DP), content.paddingTop)
        if (params.topMargin != top || params.height != height) { params.topMargin = top; params.height = height; hero.layoutParams = params }
        val bar = topBar.layoutParams as? FrameLayout.LayoutParams ?: return
        val barTop = insetTop + dp(4)
        if (bar.topMargin != barTop) { bar.topMargin = barTop; topBar.layoutParams = bar }
    }
    fun updateHomeHero() {
        if (screen != "Home" || groupIds != null || homeHero == null || !::heroArt.isInitialized || tracks.isEmpty()) return
        val preview = PlaybackVisualState.current(player)
        val sharedArt = preview?.sharedArtwork
        // During reconnection, do not substitute an unrelated library cover.
        val coverTrack = when {
            preview?.shared == true -> null
            preview != null -> preview.mediaId.toLongOrNull()?.let(::findTrack)
            else -> null
        }
        val key = if (preview?.shared == true) "shared:${preview.mediaId}:${sharedArt?.contentHashCode()}"
            else if (preview != null) "track:${preview.mediaId}:${coverTrack?.id}"
            else if (playbackConnecting) "connecting" else "empty"
        if (heroArtKey != key || heroArt.childCount == 0) {
            heroArt.removeAllViews()
            val cover = when {
                sharedArt != null -> sharedArtworkView(sharedArt, dp(360))
                preview?.shared == true || (preview != null && coverTrack == null) -> label("♫", 56f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                coverTrack != null -> coverView(coverTrack, dp(360))
                else -> null
            }
            if (cover != null) heroArt.addView(cover, FrameLayout.LayoutParams(-1, -1))
            heroArtKey = key
        }
        heroEyebrow.text = if (preview == null) "YOUR MUSIC" else if (preview.playing) "NOW PLAYING" else "PAUSED"
        heroTitle.text = preview?.title ?: if (preview == null) "Ready when you are" else "Untitled"
        heroArtist.text = preview?.artist ?: if (preview == null) "${tracks.size} songs on this device" else "Unknown artist"
        heroPlay.setImageResource(if (preview?.playing == true) R.drawable.ny_pause else R.drawable.ny_play)
        heroPlay.contentDescription = if (preview?.playing == true) "Pause" else "Play"
    }
    private fun addVibeRow(container: LinearLayout, track: Track, list: List<Track>, index: Int) {
        val entry = row().apply {
            minimumHeight = dp(58); setPadding(0, dp(3), 0, dp(3))
            isClickable = true; isFocusable = true; background = pressable(bg, 12)
            TrackTouchFeedback.apply(this)
            contentDescription = "Play ${track.title}, ${track.artist}"
            setOnClickListener { playList(list, index, sourceOverride = "Instant Vibes") }
            setOnLongClickListener { vibeTrackMenu(track); true }
        }
        entry.addView(coverView(track, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        val text = col()
        val title = label(track.title, 15f, onSurface, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false
            setLineHeight(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 19f, context.resources.displayMetrics).toInt())
        }
        text.addView(title)
        val stat = history[track.id]
        val age = System.currentTimeMillis() - (stat?.lastPlayedMs ?: 0L)
        val reason = when {
            track.id in favorites -> "Liked song"
            (stat?.plays ?: 0) == 0 -> "Not played yet"
            (stat?.lastPlayedMs ?: 0L) > 0 && age >= 30L * 24 * 60 * 60 * 1000 -> "Played a while ago"
            else -> "${stat?.plays ?: 0} ${(if (stat?.plays == 1) "play" else "plays")}"
        }
        val detailBase = "${track.artist} · $reason"
        val detail = label(detailBase, 13f, muted).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false; setPadding(0, dp(3), 0, 0)
            setLineHeight(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics).toInt())
        }
        text.addView(detail)
        entry.addWeighted(text)
        val queueIcons = QueueStatusIcons(context, accent)
        entry.addView(queueIcons, LinearLayout.LayoutParams(-2, dp(24)))
        val status = PlayingIndicator(context, accent)
        entry.addView(status, LinearLayout.LayoutParams(dp(24), dp(24)))
        entry.addView(iconButton(R.drawable.ny_more, "Options for ${track.title}", bg, onSurface) { vibeTrackMenu(track) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        vibePlayingViews.add(VibePlayingView(track.id, title, status, queueIcons, entry, track.title, track.artist))
        container.addView(entry, LinearLayout.LayoutParams(-1, -2))
    }
    fun updateVibePlayingState() {
        val preview = PlaybackVisualState.current(player)
        val id = preview?.mediaId?.toLongOrNull()
        vibePlayingViews.forEach { item ->
            val current = item.id == id
            item.title.setTextColor(if (current) accent else onSurface)
            item.status.setState(current, preview?.playing == true)
            if (!current) item.status.visibility = View.GONE
            val queueLabel = queueStatus(item.id)
            item.queueIcons.setStatus(queueLabel)
            item.root.contentDescription = "Play ${item.songTitle}, ${item.artist}${queueLabel?.let { ", $it" } ?: ""}"
        }
    }
    private fun homeSection(title: String, subtitle: String) {
        homeBody?.addView(label(title, 22f, onSurface, true).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(24), 0, dp(3)) })
        homeBody?.addView(label(subtitle, 13f, muted).apply { setPadding(0, dp(1), 0, dp(12)) })
    }
    private fun homeCarousel(fill: (LinearLayout) -> Unit) {
        val strip = row().apply { gravity = Gravity.TOP; setPadding(dp(16), 0, dp(6), 0); clipToPadding = false }
        fill(strip)
        homeBody?.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(strip)
        }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -dp(16); rightMargin = -dp(16) })
    }
    private fun homeCard(container: LinearLayout, title: String, subtitle: String, list: List<Track>) {
        if (list.isEmpty()) return
        val width = (context.resources.configuration.screenWidthDp * 0.44f).toInt().coerceIn(128, 196)
        val card = col().apply {
            background = pressable(bg, 16); isClickable = true; isFocusable = true
            contentDescription = "$title. $subtitle"; PressFeedback.apply(this)
        }
        val tile = FrameLayout(context).apply { background = shape(tileSurface, 16); clipToOutline = true }
        tile.addView(collageView(list, dp(width)), FrameLayout.LayoutParams(-1, -1))
        card.setOnClickListener { openGroup(title, list, tile) }
        card.addView(tile, LinearLayout.LayoutParams(-1, dp(width)))
        card.addView(label(title, 15f, onSurface, true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(3))
        })
        card.addView(label(subtitle, 13f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        container.addView(card, LinearLayout.LayoutParams(dp(width), -2).apply { marginEnd = dp(14) })
    }

}
