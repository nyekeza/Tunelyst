package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player

/** Accessible 48dp repeat control with visible ALL / 1 state. */
internal class RepeatControl(context: Context, private val foreground: Int, private val accent: Int, action: () -> Unit) : FrameLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ny_repeat)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val stateLabel = TextView(context).apply {
        textSize = 8f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        minimumWidth = dp(48); minimumHeight = dp(48)
        isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), ColorDrawable(Color.TRANSPARENT), null)
        addView(icon, LayoutParams(dp(25), dp(25), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(5) })
        addView(stateLabel, LayoutParams(-1, dp(14), Gravity.BOTTOM).apply { bottomMargin = dp(2) })
        setOnClickListener { action() }
        setMode(Player.REPEAT_MODE_OFF)
    }
    fun setMode(mode: Int) {
        val color = if (mode == Player.REPEAT_MODE_OFF) foreground else accent
        icon.imageTintList = ColorStateList.valueOf(color); stateLabel.setTextColor(color)
        stateLabel.text = when (mode) { Player.REPEAT_MODE_ALL -> "ALL"; Player.REPEAT_MODE_ONE -> "1"; else -> "" }
        contentDescription = when (mode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" } + ". Tap to change."
    }
    companion object {
        fun next(mode: Int) = when (mode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
}
