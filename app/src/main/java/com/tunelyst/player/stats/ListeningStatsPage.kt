package com.tunelyst.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Date

/** Scrollable, artwork-led statistics within the Home panel. */
internal class ListeningStatsPage(
    private val context: Context, private val surface: Int, private val text: Int,
    private val muted: Int, private val accent: Int, private val cover: (Track, Int) -> View
) {
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false) = TextView(context).apply {
        this.text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun section(value: String) = label(value, 16f, text, true).apply { setPadding(0, dp(24), 0, dp(10)) }
    val view = column().apply { addView(label("Loading listening stats…", 14f, muted)) }

    fun showError() {
        view.removeAllViews(); view.addView(label("Couldn’t load listening statistics. Go back and try again.", 14f, muted))
    }
    fun show(stats: ListeningStatsSummary.Snapshot) {
        view.removeAllViews()
        val banner = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                ColorUtils.blendARGB(surface, accent, 0.22f), ColorUtils.blendARGB(surface, accent, 0.06f)
            )).apply { cornerRadius = dp(18).toFloat() }
            setPadding(dp(16), dp(15), dp(16), dp(14))
        }
        banner.addView(label("YOUR LISTENING", 10f, muted, true).apply { letterSpacing = 0.1f })
        banner.addView(label(stats.plays.toString(), 32f, text, true).apply { setPadding(0, dp(3), 0, 0) })
        banner.addView(label(if (stats.plays == 1L) "recorded play" else "recorded plays", 13f, muted))
        banner.addView(label("All time · on this device", 11f, muted).apply { setPadding(0, dp(10), 0, 0) })
        view.addView(banner, LinearLayout.LayoutParams(-1, -2))
        val metrics = row().apply { gravity = Gravity.TOP }
        fun metric(number: String, title: String): View = column().apply {
            background = shape(surface, 14); setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(label(number, 20f, accent, true))
            addView(label(title, 12f, muted))
            contentDescription = "$number $title"
        }
        metrics.addView(metric(stats.songs.toString(), "songs played"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        metrics.addView(metric(stats.skips.toString(), "early skips"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        view.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (stats.lastPlayedMs > 0) {
            val date = android.text.format.DateFormat.getMediumDateFormat(context).format(Date(stats.lastPlayedMs))
            view.addView(label("Last recorded play · $date", 12f, muted).apply { setPadding(dp(2), dp(12), 0, 0) })
        }
        if (stats.plays == 0L) {
            view.addView(label("Listen to songs to start building your stats.", 14f, muted).apply { setPadding(0, dp(22), 0, 0) })
        } else {
            fun song(index: Int, song: ListeningStatsSummary.Song, detail: String, showRank: Boolean) {
                val line = row().apply { setPadding(0, dp(4), 0, dp(4)) }
                if (showRank) line.addView(label("${index + 1}", 13f, muted, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), dp(48)))
                val art = FrameLayout(context).apply { clipToOutline = true; background = shape(surface, 10); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                art.addView(cover(song.track, dp(44)), FrameLayout.LayoutParams(-1, -1))
                line.addView(art, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
                val names = column()
                names.addView(label(song.track.title, 14f, text, true).apply { maxLines = 1 })
                names.addView(label(detail, 12f, muted).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
                line.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
                view.addView(line, LinearLayout.LayoutParams(-1, -2))
            }
            if (stats.topSongs.isNotEmpty()) {
                view.addView(section("Most played songs"))
                stats.topSongs.forEachIndexed { index, s -> song(index, s, "${s.track.artist} · ${s.plays} ${if (s.plays == 1) "play" else "plays"}", true) }
            }
            if (stats.topArtists.isNotEmpty()) {
                view.addView(section("Most played artists"))
                stats.topArtists.forEachIndexed { index, artist ->
                    val line = row().apply { setPadding(0, dp(4), 0, dp(4)) }
                    line.addView(label("${index + 1}", 13f, muted, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), dp(44)))
                    line.addView(label(artist.name.take(1).uppercase(), 16f, text, true).apply {
                        gravity = Gravity.CENTER; background = shape(ColorUtils.blendARGB(surface, accent, 0.25f), 22)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
                    val names = column(); names.addView(label(artist.name, 14f, text, true).apply { maxLines = 1 })
                    names.addView(label("${artist.plays} ${if (artist.plays == 1L) "play" else "plays"} · ${artist.songs} ${if (artist.songs == 1) "song" else "songs"}", 12f, muted).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
                    line.addView(names, LinearLayout.LayoutParams(0, -2, 1f)); view.addView(line)
                }
            }
            if (stats.recentSongs.isNotEmpty()) {
                view.addView(section("Recently played"))
                val date = android.text.format.DateFormat.getMediumDateFormat(context)
                stats.recentSongs.forEachIndexed { i, s -> song(i, s, "${s.track.artist} · ${date.format(Date(s.lastPlayedMs))}", false) }
            }
            if (stats.unavailableSongs > 0) {
                view.addView(label("${stats.unavailableSongs} played ${if (stats.unavailableSongs == 1) "song is" else "songs are"} not currently available in the library; their counts remain included.", 12f, muted).apply { setPadding(0, dp(12), 0, 0) })
            }
        }
        view.addView(label("A play is counted after 30 seconds, or after a shorter song finishes (at least 2 seconds). Early skips are manual track changes after 2–30 seconds.", 12f, muted).apply { maxLines = Int.MAX_VALUE; setPadding(0, dp(20), 0, dp(16)) })
    }
}
