package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Bottom destinations, selection animation and accessible tab semantics. */
internal class BottomNavigation(private val host: Host) {
    interface Host {
        val context: Context
        val bottomNav: LinearLayout
        val screen: String
        val settingsReturnScreen: String
        val playerReturnScreen: String
        val accent: Int
        val deviceColors: Boolean
        fun dp(value: Int): Int
        fun col(): LinearLayout
        fun shape(color: Int, radius: Int): GradientDrawable
        fun label(text: String, size: Float, color: Int): TextView
        fun gradientIcon(resource: Int): Drawable?
        fun navigate(destination: String)
    }
    private var lastDestination: String? = null
    fun render() = with(host) {
        val options = listOf("Home" to R.drawable.ny_tab_home, "Search" to R.drawable.ny_tab_search,
            "Library" to R.drawable.ny_tab_library, "Liked" to R.drawable.ny_tab_liked)
        val destination = when (screen) {
            "Settings" -> settingsReturnScreen
            "Player", "Queue" -> if (playerReturnScreen == "Settings") settingsReturnScreen else playerReturnScreen
            else -> screen
        }.takeIf { value -> options.any { it.first == value } } ?: "Home"
        if (lastDestination == destination && bottomNav.childCount == options.size) return@with
        val animate = lastDestination != null && lastDestination != destination && android.animation.ValueAnimator.areAnimatorsEnabled()
        lastDestination = destination
        bottomNav.removeAllViews()
        options.forEach { (name, outlineIcon) ->
            val active = destination == name
            val item = col().apply {
                gravity = Gravity.CENTER; minimumWidth = dp(48); minimumHeight = dp(56)
                setPadding(dp(2), dp(2), dp(2), dp(2)); background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x33ffffff), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), shape(Color.WHITE, 16))
                contentDescription = name; isSelected = active; isClickable = true; isFocusable = true
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name; info.isSelected = active
                    }
                }
                PressFeedback.apply(this); setOnClickListener { navigate(name) }
            }
            val marker = FrameLayout(context).apply {
                if (active) background = shape(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 36), 18)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val image = ImageView(context).apply {
                if (active && !deviceColors) { setImageDrawable(gradientIcon(outlineIcon)); imageTintList = null }
                else { setImageResource(outlineIcon); imageTintList = ColorStateList.valueOf(if (active) accent else Color.WHITE) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            marker.addView(image, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
            item.addView(marker, LinearLayout.LayoutParams(dp(52), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            item.addView(label(name, 11f, if (active) accent else Color.WHITE).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0); setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            bottomNav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            if (active && animate) { marker.alpha = 0f; marker.scaleX = 0.85f; marker.animate().alpha(1f).scaleX(1f).setDuration(180).start() }
        }
    }
}
