package com.tunelyst.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/** Visible only while the row is pressed; does not interfere with clicks, scrolling or drag listeners. */
internal object TrackTouchFeedback {
    fun apply(view: View, radiusDp: Int = 12) {
        val radius = radiusDp * view.resources.displayMetrics.density
        fun fill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        view.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), fill(0x33ffffff))
            addState(intArrayOf(), fill(Color.TRANSPARENT))
        }
    }
}
