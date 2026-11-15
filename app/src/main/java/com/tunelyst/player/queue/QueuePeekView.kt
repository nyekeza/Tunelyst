package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player

/** Accessible collapsed entry point: tap or swipe up to open Up next. */
internal class QueuePeekView(context: Context, surface: Int, private val textColor: Int, private val secondary: Int,
    private val cover: (String?) -> View?, open: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val heading = TextView(context).apply { textSize = 10f; setTextColor(secondary); setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val title = TextView(context).apply { textSize = 15f; setTextColor(textColor); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val detail = TextView(context).apply { textSize = 12f; setTextColor(secondary); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val artwork = FrameLayout(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var artworkKey: String? = null
    private var artworkFound = false
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = dp(64); setPadding(dp(14), dp(6), dp(10), dp(6))
        val bg = GradientDrawable().apply { setColor(surface); cornerRadius = dp(18).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), bg, null)
        isClickable = true; isFocusable = true; contentDescription = "Up next. Tap or swipe up to open queue"
        PressFeedback.apply(this); setOnClickListener { open() }
        addView(View(context).apply {
            background = GradientDrawable().apply { setColor(secondary); cornerRadius = dp(4).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(36), dp(3)).apply { bottomMargin = dp(4) })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(artwork, LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(heading)
        labels.addView(title)
        labels.addView(detail)
        refresh(null)
        row.addView(labels, LayoutParams(0, -2, 1f))
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ny_expand_up); imageTintList = ColorStateList.valueOf(textColor)
            setPadding(dp(12), dp(12), dp(12), dp(12)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(48), dp(48)))
        addView(row, LayoutParams(-1, -2))
        val threshold = ViewConfiguration.get(context).scaledTouchSlop * 2
        var startY = 0f; var startX = 0f; var opened = false
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; startX = event.x; opened = false; view.isPressed = true; true }
                MotionEvent.ACTION_MOVE -> {
                    if (!opened && startY - event.y > threshold && kotlin.math.abs(event.x - startX) < startY - event.y) {
                        opened = true; view.isPressed = false; view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (!opened && event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; true }
                else -> true
            }
        }
    }
    /** Refresh on player events, not the progress ticker. */
    fun refresh(player: Player?) {
        val upcoming = if (player != null) QueueTraversal.upcoming(player) else emptyList()
        val current = player?.currentMediaItem
        val repeating = current != null && (player.repeatMode == Player.REPEAT_MODE_ONE ||
            (player.repeatMode == Player.REPEAT_MODE_ALL && upcoming.isEmpty()))
        val next = when {
            player == null -> null
            repeating -> current
            upcoming.isNotEmpty() -> player.getMediaItemAt(upcoming.first())
            else -> null
        }
        val nextTitle = next?.mediaMetadata?.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (next != null) "Untitled" else null
        val display = QueuePreviewModel.presentation(player != null, upcoming.size, nextTitle,
            next?.mediaMetadata?.artist?.toString(), repeating)
        if (heading.text != display.heading) heading.text = display.heading
        if (title.text != display.title) title.text = display.title
        if (detail.text != display.detail) detail.text = display.detail
        val key = next?.mediaId ?: ""
        if (artworkKey != key || (!artworkFound && next != null)) {
            artworkKey = key; artwork.removeAllViews()
            val image = if (next != null) cover(next.mediaId) else null
            artworkFound = image != null
            artwork.addView(image ?: ImageView(context).apply {
                background = PlaceholderArtwork.gradient("queue-peek:$key", dp(10).toFloat())
                setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }
        contentDescription = display.accessibility
    }

}
