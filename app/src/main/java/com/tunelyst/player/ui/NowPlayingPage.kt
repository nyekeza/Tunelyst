package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player

/** Builds the Now Playing page; playback and navigation remain owned by the activity. */
internal object NowPlayingPage {
    interface Host {
        val context: Context
        val content: LinearLayout
        val player: Player?
        val tracks: List<Track>
        val favorites: Set<Long>
        val bg: Int
        val surface: Int
        val onSurface: Int
        val accent: Int
        val muted: Int
        val onTile: Int
        val tileSurface: Int
        fun dp(value: Int): Int
        fun row(): LinearLayout
        fun col(): LinearLayout
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun shape(color: Int, radius: Int): Drawable
        fun pressable(color: Int, radius: Int): Drawable
        fun cover(track: Track, size: Int): View
        fun sharedCover(data: ByteArray, size: Int): View
        fun applyGradient(track: Track?, target: View)
        fun back()
        fun showMenu()
        fun openArtist()
        fun toggleFavorite(track: Track?)
        fun seekStarted()
        fun seekStopped(progress: Int)
        fun toggleSmartShuffle()
        fun toggleRepeat()
        fun togglePlayback()
        fun previous()
        fun next()
    }
    data class Views(
        val title: TextView, val subtitle: TextView, val play: ImageButton,
        val menu: ImageButton, val repeat: RepeatControl, val smart: ImageButton,
        val seek: SeekBar, val elapsed: TextView, val total: TextView
    )
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }

    fun render(h: Host): Views = with(h) {
        val p = player
        val heading = row().apply { setPadding(0, dp(4), 0, dp(14)) }
        heading.addView(iconButton(R.drawable.ny_back, "Back", surface, onSurface) { back() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val headingText = col().apply { gravity = Gravity.CENTER }
        headingText.addView(label("NOW PLAYING", 12f, onSurface, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f })
        val source = p?.currentMediaItem?.mediaMetadata?.extras?.getString("tunelyst.source")
        headingText.addView(label(source ?: "On your device", 11f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        heading.addWeighted(headingText)
        val menuButton = iconButton(R.drawable.ny_menu, "Playback menu", bg, onSurface) { showMenu() }
        heading.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(heading)
        content.addView(View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(1, 0, 0.35f))
        val current = p?.currentMediaItem?.mediaId?.toLongOrNull()?.let { id -> tracks.firstOrNull { it.id == id } }
        applyGradient(current, content)
        val resources = context.resources
        val availableArtWidth = (resources.configuration.screenWidthDp - 32).coerceAtLeast(96)
        val artDp = minOf(availableArtWidth, (resources.configuration.screenHeightDp - 340).coerceAtLeast(160), 460)
        val artSize = dp(artDp)
        val sharedArt = if (SharedAudio.isShared(p?.currentMediaItem)) p?.mediaMetadata?.artworkData else null
        val art = if (current != null) cover(current, artSize) else if (sharedArt != null) sharedCover(sharedArt, artSize) else label("♫", 56f, onTile, true).apply { gravity = Gravity.CENTER; background = shape(tileSurface, 24) }
        content.addView(art, LinearLayout.LayoutParams(artSize, artSize).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(28) })
        // Configuration dimensions can exceed the actual content width in a
        // resized window. Keep the cover square and inside the page padding.
        content.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val usable = (right - left - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
            val fit = minOf(artSize, usable)
            val params = art.layoutParams as? LinearLayout.LayoutParams
            if (params != null && (params.width != fit || params.height != fit)) {
                params.width = fit; params.height = fit; art.layoutParams = params
            }
        }
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            art.alpha = 0f; art.scaleX = 0.97f; art.scaleY = 0.97f
            art.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
        }
        content.addView(View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(1, 0, 1f))
        val titleRow = row()
        val texts = col()
        val title = label("Nothing playing", 26f, onSurface, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val subtitle = label("Choose a song from your library", 15f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        subtitle.minHeight = dp(48); subtitle.gravity = Gravity.CENTER_VERTICAL
        subtitle.setOnClickListener { openArtist() }
        texts.addView(title); texts.addView(subtitle); titleRow.addWeighted(texts)
        titleRow.addView(iconButton(R.drawable.ny_heart, "Like or unlike current song", bg, if (current != null && current.id in favorites) accent else onSurface) {
            toggleFavorite(current)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(titleRow)
        val seek = SeekBar(context).apply { max = 1000; progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent) }
        content.addView(seek, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) { seekStarted() }
            override fun onStopTrackingTouch(bar: SeekBar?) { seekStopped(bar?.progress ?: 0) }
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
        })
        val times = row(); val elapsed = label("0:00", 13f, muted); val total = label("0:00", 13f, muted).apply { gravity = Gravity.END }
        times.addWeighted(elapsed); times.addWeighted(total); content.addView(times)
        val controls = row().apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }
        val smart = iconButton(R.drawable.ny_smart_shuffle, "Smart Shuffle off", bg, Color.WHITE) { toggleSmartShuffle() }.apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
        val repeat = RepeatControl(context, onSurface, accent) { toggleRepeat() }
        val play = iconButton(R.drawable.ny_play, "Play", onSurface, bg) { togglePlayback() }.apply { setPadding(dp(17), dp(17), dp(17), dp(17)); background = pressable(onSurface, 36); imageTintList = ColorStateList.valueOf(bg) }
        val prev = iconButton(R.drawable.ny_previous, "Previous song", bg, onSurface) { previous() }
        val next = iconButton(R.drawable.ny_next, "Next song", bg, onSurface) { next() }
        fun slot(view: View, size: Int) {
            val holder = FrameLayout(context)
            holder.addView(view, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
            controls.addView(holder, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        val available = (resources.configuration.screenWidthDp - 32).coerceAtLeast(240)
        val playSize = ((available / 5) * 2 - 48).coerceIn(48, 68)
        slot(smart, 48); slot(prev, 48); slot(play, playSize); slot(next, 48); slot(repeat, 48)
        controls.minimumWidth = dp(240)
        val controlsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(controls)
        }
        content.addView(controlsScroll, LinearLayout.LayoutParams(-1, -2))
        Views(title, subtitle, play, menuButton, repeat, smart, seek, elapsed, total)
    }
}
