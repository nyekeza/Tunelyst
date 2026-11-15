package com.tunelyst.player

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Constructs the reusable activity chrome; navigation and state remain with the host. */
internal class ActivityShell(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val bg: Int
        val surface: Int
        val onSurface: Int
        val muted: Int
        val accent: Int
        val tileSurface: Int
        val onTile: Int
        val player: MediaController?
        val tracks: List<Track>
        var query: String
        fun dp(n: Int): Int
        fun shape(color: Int, radius: Int): GradientDrawable
        fun pressable(color: Int, radius: Int): Drawable
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun button(text: String, action: () -> Unit): Button
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun col(): LinearLayout
        fun row(): LinearLayout
        fun showSidePanel()
        fun openPlayer()
        fun togglePlayback()
        fun rememberQuery(value: String)
        fun queryChanged()
        fun selectionActions()
        fun selectionDone()
        fun coverView(track: Track, size: Int): View
        fun showQueue()
        fun updatePagePadding()
    }
    lateinit var screenHost: FrameLayout
    lateinit var rootLayout: LinearLayout
    lateinit var topChrome: LinearLayout
    lateinit var statusScrim: View
    lateinit var bottomDock: LinearLayout
    lateinit var topBar: LinearLayout
    lateinit var homeBrandTitle: TextView
    lateinit var searchHeading: TextView
    lateinit var searchInput: EditText
    lateinit var searchContainer: FrameLayout
    lateinit var searchClear: ImageButton
    lateinit var searchCategoryTabs: LinearLayout
    lateinit var searchCategoryScroll: HorizontalScrollView
    lateinit var libraryHeading: TextView
    lateinit var tabs: LinearLayout
    lateinit var tabScroll: HorizontalScrollView
    lateinit var libraryScroll: ScrollView
    lateinit var content: LinearLayout
    lateinit var listHost: FrameLayout
    lateinit var queueHost: FrameLayout
    lateinit var miniPlayer: FrameLayout
    lateinit var miniArt: FrameLayout
    lateinit var miniTitle: TextView
    lateinit var miniArtist: TextView
    lateinit var miniPlay: ImageButton
    lateinit var miniNext: ImageButton
    lateinit var miniProgress: ProgressBar
    lateinit var selectionBar: LinearLayout
    lateinit var selectionCount: TextView
    lateinit var queuePeek: QueuePeekView
    lateinit var bottomNav: LinearLayout
    lateinit var bottomNavHost: HorizontalScrollView
    lateinit var bottomNavShell: FrameLayout
    private fun LinearLayout.addWeighted(view: View, weight: Float = 1f) { addView(view, LinearLayout.LayoutParams(0, -2, weight)) }
    fun build() = with(host) {
        screenHost = FrameLayout(activity).apply { setBackgroundColor(bg); clipChildren = true }
        val root = col().apply { clipChildren = true; clipToPadding = false }
        rootLayout = root
        screenHost.addView(root, FrameLayout.LayoutParams(-1, -1))
        // Search and library controls float over the page, which can scroll under the status bar.
        topChrome = col().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xe8000000.toInt(), 0xd0000000.toInt(), 0x66000000))
            clipChildren = true
        }
        screenHost.addView(topChrome, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        statusScrim = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xb8000000.toInt(), 0x88000000.toInt(), Color.TRANSPARENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        screenHost.addView(statusScrim, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
        bottomDock = col().apply { clipChildren = true; clipToPadding = false }
        screenHost.addView(bottomDock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val brand = row().apply { setPadding(0, dp(4), 0, dp(4)) }
        topBar = brand
        val logo = ImageView(activity).apply {
            setImageResource(R.drawable.tunelyst_icon); scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(tileSurface, 14); clipToOutline = true; contentDescription = "Open Tunelyst menu"
            isClickable = true; isFocusable = true; setOnClickListener { showSidePanel() }
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(10) })
        val branding = col()
        homeBrandTitle = label("Tunelyst", 23f, onSurface, true)
        branding.addView(homeBrandTitle)
        brand.addWeighted(branding)
        // Added to the Home page, not the fixed chrome: it scrolls naturally away.
        searchHeading = label("Search", 23f, onSurface, true).apply { setAccessibilityHeading(true) }
        topChrome.addView(searchHeading, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8) })
        searchInput = EditText(activity).apply {
            hint = "Search songs, artists, albums"; setSingleLine(true); textSize = 15f; setTextColor(onSurface); setHintTextColor(muted)
            background = shape(surface, 18); minimumHeight = dp(52); setPadding(dp(16), dp(10), dp(56), dp(10)); setText(query)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, event ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)) {
                    rememberQuery(query)
                    clearFocus()
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    if (::searchClear.isInitialized) searchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                    queryChanged()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchContainer = FrameLayout(activity)
        searchContainer.addView(searchInput, FrameLayout.LayoutParams(-1, -2))
        searchClear = iconButton(R.drawable.ny_close, "Clear search", surface, onSurface) { searchInput.setText(""); searchInput.requestFocus() }.apply {
            visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        }
        searchContainer.addView(searchClear, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        topChrome.addView(searchContainer, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8); bottomMargin = dp(2) })
        searchCategoryTabs = row()
        searchCategoryScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFillViewport = false; addView(searchCategoryTabs) }
        topChrome.addView(searchCategoryScroll, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(4) })
        libraryHeading = label("Your Library", 23f, onSurface, true).apply { setAccessibilityHeading(true) }
        topChrome.addView(libraryHeading, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8) })
        tabs = row()
        tabScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFillViewport = false; addView(tabs) }
        topChrome.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8); bottomMargin = dp(4) })
        libraryScroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipChildren = true; clipToPadding = true }
        content = col().apply { setPadding(dp(16), dp(8), dp(16), dp(12)); clipChildren = false; clipToPadding = false }
        libraryScroll.addView(content)
        // Bound the scrolling page to its own viewport so it cannot paint over
        // the fixed header, status area, mini player, or navigation.
        val body = FrameLayout(activity).apply { clipChildren = true; clipToPadding = true }
        body.addView(libraryScroll, FrameLayout.LayoutParams(-1, -1))
        listHost = FrameLayout(activity).apply { clipChildren = true; clipToPadding = true }
        body.addView(listHost, FrameLayout.LayoutParams(-1, -1))
        queueHost = FrameLayout(activity)
        body.addView(queueHost, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        miniPlayer = FrameLayout(activity).apply {
            background = pressable(tileSurface, 18); clipToOutline = true
            isClickable = true; isFocusable = true; contentDescription = "Open now playing"
            setOnClickListener { openPlayer() }; visibility = View.GONE
        }
        val miniRow = row().apply { setPadding(dp(8), dp(7), dp(8), dp(9)); minimumHeight = dp(64) }
        miniArt = FrameLayout(activity)
        miniRow.addView(miniArt, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        val miniLabels = col()
        miniTitle = label("", 15f, onTile, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniArtist = label("", 13f, onTile).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.8f }
        miniLabels.addView(miniTitle); miniLabels.addView(miniArtist); miniRow.addWeighted(miniLabels)
        miniPlay = iconButton(R.drawable.ny_play, "Play", tileSurface, onTile) { togglePlayback() }
        miniNext = iconButton(R.drawable.ny_next, "Next song", tileSurface, onTile) {
            player?.let { if (it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }
        miniRow.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniRow.addView(miniNext, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniPlayer.addView(miniRow, FrameLayout.LayoutParams(-1, -2))
        miniProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(onTile)
            progressBackgroundTintList = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(onTile, 55))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        miniPlayer.addView(miniProgress, FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM))
        selectionBar = row().apply { background = shape(surface, 18); visibility = View.GONE; setPadding(dp(10), 0, dp(6), 0); minimumHeight = dp(52) }
        selectionCount = label("0 selected", 14f, onSurface, true)
        selectionBar.addWeighted(selectionCount)
        selectionBar.addView(button("Actions") { selectionActions() }, LinearLayout.LayoutParams(-2, dp(48)))
        selectionBar.addView(button("Done") { selectionDone() }, LinearLayout.LayoutParams(-2, dp(48)))
        bottomDock.addView(selectionBar, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(4) })
        bottomDock.addView(miniPlayer, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4); bottomMargin = dp(4) })
        queuePeek = QueuePeekView(activity, surface, onSurface, muted, { id ->
            id?.toLongOrNull()?.let { key -> tracks.firstOrNull { it.id == key } }?.let { coverView(it, dp(44)) }
        }) { showQueue() }.apply { visibility = View.GONE }
        bottomDock.addView(queuePeek, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4); bottomMargin = dp(4) })
        bottomNav = row().apply {
            minimumWidth = dp(192); minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
        }
        bottomNavHost = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(bottomNav)
        }
        // A drawable scrim keeps the tabs legible over artwork without an opaque bar.
        // Never use MATCH_PARENT background children in this wrap-content shell.
        bottomNavShell = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x18000000, 0x74000000, 0xae000000.toInt(), 0xce000000.toInt()))
        }
        bottomNavShell.addView(bottomNavHost, FrameLayout.LayoutParams(-1, -2))
        bottomDock.addView(bottomNavShell, LinearLayout.LayoutParams(-1, -2))
        bottomDock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePagePadding() }
        topChrome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePagePadding() }
    }
}
