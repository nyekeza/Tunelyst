package com.tunelyst.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.LruCache
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** Owns the cached, asynchronous artwork color wash used by player and collection pages. */
internal class ArtworkGradientRenderer(
    private val activity: AppCompatActivity,
    private val artworkIo: Executor,
    private val main: Handler
) {
    private val artworkColors = object : LruCache<Long, Int>(64) {}
    fun invalidate() { artworkColors.evictAll() }

    /** A dark, legible wash sampled from local artwork; no network or blur. */
    fun apply(track: Track?, target: View, overlay: Boolean, accent: Int) {
        fun paint(color: Int) {
            val dark = androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, 0.70f)
            val top = if (overlay) androidx.core.graphics.ColorUtils.setAlphaComponent(dark, 80) else dark
            val middle = if (overlay) 0x66000000.toInt() else androidx.core.graphics.ColorUtils.blendARGB(dark, Color.BLACK, 0.55f)
            target.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, middle, if (overlay) 0xf5000000.toInt() else Color.BLACK))
        }
        paint(accent)
        if (track == null || track.albumId < 0) return
        val cached = artworkColors.get(track.albumId)
        if (cached != null) { paint(cached); return }
        val weak = WeakReference(target)
        val fallback = accent
        artworkIo.execute {
            val sampled = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(track.artwork)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 48) sample *= 2
                    val bitmap = activity.contentResolver.openInputStream(track.artwork)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    bitmap?.let {
                        try { sampleArtworkColor(it) } finally { it.recycle() }
                    }
                }
            } catch (_: Exception) { null }
            main.post {
                if (!activity.isDestroyed) {
                    val color = sampled ?: fallback
                    artworkColors.put(track.albumId, color)
                    weak.get()?.let { view ->
                        val dark = androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, 0.70f)
                        val top = if (overlay) androidx.core.graphics.ColorUtils.setAlphaComponent(dark, 80) else dark
                        val middle = if (overlay) 0x66000000.toInt() else androidx.core.graphics.ColorUtils.blendARGB(dark, Color.BLACK, 0.55f)
                        view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(top, middle, if (overlay) 0xf5000000.toInt() else Color.BLACK))
                    }
                }
            }
        }
    }
    private fun sampleArtworkColor(bitmap: Bitmap): Int? {
        var red = 0L; var green = 0L; var blue = 0L; var total = 0L
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            val max = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            val min = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            if (Color.alpha(pixel) >= 128 && max >= 35 && min <= 230) {
                val weight = (max - min + 32).toLong()
                red += Color.red(pixel) * weight; green += Color.green(pixel) * weight; blue += Color.blue(pixel) * weight; total += weight
            }
        }
        return if (total == 0L) null else Color.rgb((red / total).toInt(), (green / total).toInt(), (blue / total).toInt())
    }
}
