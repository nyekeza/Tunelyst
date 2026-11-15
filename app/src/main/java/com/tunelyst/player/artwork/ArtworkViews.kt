package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Reusable artwork and four-cell collection previews. */
internal class ArtworkViews(private val host: Host) {
    interface Host {
        val context: Context
        val tileSurface: Int
        val surface: Int
        val muted: Int
        val loader: ArtworkLoader
        fun dp(value: Int): Int
        fun shape(color: Int, radius: Int): GradientDrawable
    }
    companion object {
        internal fun previewTracks(list: List<Track>): List<Track> = list.asSequence()
            .distinctBy { if (it.albumId >= 0) "album:${it.albumId}" else "track:${it.id}" }.take(4).toList()
    }
    fun collage(list: List<Track>, size: Int): View = with(host) {
        val covers = previewTracks(list)
        val frame = FrameLayout(context).apply { background = shape(tileSurface, 16); clipToOutline = true }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        repeat(2) { rowIndex ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            repeat(2) { column ->
                val track = if (covers.isEmpty()) null else covers[(rowIndex * 2 + column) % covers.size]
                val tile = if (track != null) cover(track, size / 2) else FrameLayout(context).apply {
                    background = shape(surface, 0)
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(muted)
                        alpha = 0.5f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }
                (tile.background as? GradientDrawable)?.cornerRadius = 0f
                tile.clipToOutline = false; tile.tag = track?.id
                line.addView(tile, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1)); frame
    }
    /** Keep the old cover visible until a changed MediaStore cover has been decoded. */
    fun refreshCover(track: Track, view: View, size: Int) = with(host) {
        val frame = view as? FrameLayout ?: return@with
        val image = frame.getChildAt(1) as? ImageView ?: return@with
        loader.loadArtwork(track, image, size, clearOnMissing = true)
    }
    fun cover(track: Track, size: Int): View = with(host) {
        val frame = FrameLayout(context).apply {
            background = shape(tileSurface, if (size > dp(200)) 26 else 12); clipToOutline = true
        }
        val key = if (track.albumId > 0 && !track.album.equals("Unknown album", true)) "${track.albumId}:${track.album}" else "track:${track.id}"
        frame.background = PlaceholderArtwork.gradient(key, dp(12).toFloat()).apply {
            cornerRadius = dp(if (size > dp(200)) 26 else 12).toFloat()
        }
        val placeholder = ImageView(context).apply {
            setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0.78f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val mark = minOf(size / 2, dp(36)).coerceAtLeast(dp(18))
        frame.addView(placeholder, FrameLayout.LayoutParams(mark, mark, Gravity.CENTER))
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        loader.loadArtwork(track, image, size); frame
    }
}
