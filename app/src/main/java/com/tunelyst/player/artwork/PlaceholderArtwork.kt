package com.tunelyst.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Stable, dark gradient pairs for tracks whose providers have no cover art. */
internal object PlaceholderArtwork {
    private val palettes = arrayOf(
        intArrayOf(0xff574381.toInt(), 0xff241d42.toInt()),
        intArrayOf(0xff24617a.toInt(), 0xff142b43.toInt()),
        intArrayOf(0xff92515a.toInt(), 0xff3b202e.toInt()),
        intArrayOf(0xff326d68.toInt(), 0xff152e38.toInt()),
        intArrayOf(0xff705793.toInt(), 0xff292141.toInt()),
        intArrayOf(0xff796141.toInt(), 0xff33252d.toInt())
    )
    fun gradient(key: String, radius: Float): GradientDrawable {
        val index = (key.hashCode() and Int.MAX_VALUE) % palettes.size
        val colors = palettes[index]
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[0], colors[1], Color.rgb(13, 16, 24))).apply { cornerRadius = radius }
    }
}
