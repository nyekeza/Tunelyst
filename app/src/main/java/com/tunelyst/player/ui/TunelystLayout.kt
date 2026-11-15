package com.tunelyst.player

import android.content.Context
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Shared rules for safe, edge-to-edge pages and the floating playback/navigation dock. */
internal object TunelystLayout {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun enableEdgeToEdge(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = android.graphics.Color.TRANSPARENT; window.navigationBarColor = android.graphics.Color.BLACK }
    }

    fun observeInsets(host: View, changed: (Int, Int, Int, Int) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            changed(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(host)
    }

    fun contentPadding(content: LinearLayout, horizontal: Int, top: Int, bottom: Int) {
        if (content.paddingLeft != horizontal || content.paddingTop != top || content.paddingRight != horizontal || content.paddingBottom != bottom) {
            content.setPadding(horizontal, top, horizontal, bottom)
        }
    }
}
