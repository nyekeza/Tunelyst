package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Compact, separate status glyphs; their meanings remain in the parent row's accessibility text. */
internal class QueueStatusIcons(context: Context, private val tint: Int) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun icon(res: Int) = ImageView(context).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val next = icon(R.drawable.ny_status_next)
    private val queued = icon(R.drawable.ny_status_queue)
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(next, LayoutParams(dp(18), dp(18)))
        addView(queued, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(2) })
        visibility = View.GONE
    }
    fun setStatus(label: String?) {
        next.visibility = if (label?.contains("Play next") == true) View.VISIBLE else View.GONE
        queued.visibility = if (label?.contains("In queue") == true) View.VISIBLE else View.GONE
        visibility = if (next.visibility == View.VISIBLE || queued.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }
}
