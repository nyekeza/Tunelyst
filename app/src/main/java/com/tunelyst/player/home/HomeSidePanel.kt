package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor

/** Sliding menu with a separate, scrollable listening-stats page. */
internal class HomeSidePanel(private val host: Host) {
    interface Host {
        val context: Context
        val screenHost: FrameLayout
        val insetTop: Int
        val insetBottom: Int
        val surface: Int
        val bg: Int
        val onSurface: Int
        val muted: Int
        val accent: Int
        val tracks: List<Track>
        fun cover(track: Track, size: Int): View
        val io: Executor
        val main: Handler
        val isDestroyed: Boolean
        fun dp(value: Int): Int
        fun col(): LinearLayout
        fun row(): LinearLayout
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun pressable(color: Int, radius: Int): Drawable
        fun openSettings()
        fun visibilityChanged()
    }
    private var overlayView: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var statsPage: ListeningStatsPage? = null
    val isOpen get() = overlayView != null
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }
    fun close() {
        val overlay = overlayView ?: return
        overlayView = null; panelView = null; statsPage = null
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        host.visibilityChanged()
    }
    fun back() { if (statsPage != null) showMenu() else close() }
    fun show(): Unit = with(host) {
        if (overlayView != null) return
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xa6000000.toInt()); isClickable = true
            setOnClickListener { close() }
        }
        val panel = col().apply {
            setBackgroundColor(surface); isClickable = true; isFocusable = true
            setPadding(dp(20), insetTop + dp(12), dp(20), insetBottom + dp(16))
            contentDescription = "Tunelyst menu"
        }
        val width = minOf(dp(320), (context.resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(220)))
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        overlayView = overlay; panelView = panel
        screenHost.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        showMenu(); visibilityChanged()
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            panel.translationX = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) width.toFloat() else -width.toFloat()
            panel.animate().translationX(0f).setDuration(220).start()
        }
    }
    private fun showMenu(): Unit = with(host) {
        val panel = panelView ?: return
        statsPage = null; panel.removeAllViews(); panel.contentDescription = "Tunelyst menu"
        val header = row()
        header.addWeighted(label("Tunelyst", 24f, onSurface, true))
        header.addView(iconButton(R.drawable.ny_close, "Close menu", surface, onSurface) { close() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)
        fun entry(title: String, icon: Int, action: () -> Unit): View = row().apply {
            minimumHeight = dp(56); background = pressable(bg, 14); setPadding(dp(12), 0, dp(12), 0)
            isClickable = true; isFocusable = true; contentDescription = "Open $title"; setOnClickListener { action() }
            addView(ImageView(context).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(onSurface)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addWeighted(label(title, 16f, onSurface, true)); addView(label("›", 24f, muted))
        }
        panel.addView(entry("Listening stats", R.drawable.ny_stats) { showStats() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        panel.addView(entry("Settings", R.drawable.ny_settings) { close(); openSettings() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun showStats(): Unit = with(host) {
        val panel = panelView ?: return
        val overlay = overlayView ?: return
        panel.removeAllViews(); panel.contentDescription = "Listening statistics"
        val header = row()
        header.addView(iconButton(R.drawable.ny_back, "Back to menu", surface, onSurface) { showMenu() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        header.addWeighted(label("Listening stats", 20f, onSurface, true))
        header.addView(iconButton(R.drawable.ny_close, "Close menu", surface, onSurface) { close() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)
        val page = ListeningStatsPage(context, bg, onSurface, muted, accent) { track, size -> cover(track, size) }
        statsPage = page
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(page.view) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        val librarySnapshot = tracks.toList()
        io.execute {
            val result = runCatching {
                val records = LibraryDb(context.applicationContext).use { it.listeningStats() }
                ListeningStatsSummary.build(records, librarySnapshot)
            }
            main.post {
                if (!isDestroyed && overlayView === overlay && statsPage === page) {
                    result.onSuccess { page.show(it) }.onFailure { page.showError() }
                }
            }
        }
    }
}
