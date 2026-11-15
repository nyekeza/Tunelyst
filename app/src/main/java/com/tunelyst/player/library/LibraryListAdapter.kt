package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** A bounded-view adapter for songs and collections. Artwork is requested only for visible rows. */
class LibraryListAdapter(
    private val context: Context,
    private val rows: List<Row>,
    private val foreground: Int,
    private val secondary: Int,
    private val accent: Int,
    private val surface: Int,
    private val onTile: Int,
    private val isLiked: (Track) -> Boolean,
    private val cover: (Track, Int) -> View,
    private val play: (Int) -> Unit,
    private val like: (Track) -> Unit,
    private val options: (Track) -> Unit,
    private val playingState: () -> Pair<Long?, Boolean> = { null to false },
    private val collage: ((List<Track>, Int) -> View)? = null,
    private val queueStatus: (Long) -> String? = { null },
    private val selection: () -> Set<Long>? = { null },
    private val toggleSelection: (Track) -> Unit = {}
) : BaseAdapter() {
    sealed class Row {
        data class Song(val track: Track, val index: Int) : Row()
        data class Section(val title: String) : Row()
        data class Collection(val title: String, val detail: String, val art: Track?, val click: () -> Unit, val hold: (() -> Unit)? = null, val artworks: List<Track> = emptyList()) : Row()
    }
    /** Creates song rows only when ListView asks for them, not for the entire library. */
    internal class SongRows(val songs: List<Track>) : AbstractList<Row>() {
        override val size: Int get() = songs.size
        override fun get(index: Int): Row = Row.Song(songs[index], index)
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(): RippleDrawable = RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape(android.graphics.Color.TRANSPARENT), null)
    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private data class ArtKey(val track: Track?, val collectionTitle: String?, val artworks: List<Track>?)
    private class Holder(val root: LinearLayout, val artwork: FrameLayout, val title: TextView, val detail: TextView,
                         val queueIcons: QueueStatusIcons, val indicator: PlayingIndicator, val heart: ImageButton,
                         val more: ImageButton, val arrow: TextView, val check: CheckBox, var artKey: ArtKey? = null)
    private fun updateSongState(h: Holder, track: Track) {
        val (playingId, isPlaying) = playingState()
        val current = track.id == playingId
        h.indicator.setState(current, current && isPlaying)
        if (!current) h.indicator.visibility = View.GONE
        h.title.setTextColor(if (current) accent else foreground)
        val queueLabel = queueStatus(track.id)
        val selected = selection()
        h.queueIcons.setStatus(if (selected == null) queueLabel else null)
        h.check.visibility = if (selected != null) View.VISIBLE else View.GONE
        h.check.isChecked = selected?.contains(track.id) == true
        h.heart.visibility = if (selected == null) View.VISIBLE else View.GONE
        h.more.visibility = if (selected == null) View.VISIBLE else View.GONE
        h.root.contentDescription = "${track.title}, ${track.artist}${if (current) if (isPlaying) ", now playing" else ", paused" else ""}${queueLabel?.let { ", $it" } ?: ""}${if (selected == null) ". Touch and hold or use song options." else if (track.id in selected) ", selected" else ", not selected"}"
    }
    /** Refresh only attached song rows; keep their artwork, scroll position and recycled views. */
    fun refreshVisible(list: ListView) {
        for (i in 0 until list.childCount) {
            val view = list.getChildAt(i)
            val position = list.getPositionForView(view) - list.headerViewsCount
            val row = rows.getOrNull(position) as? Row.Song ?: continue
            val holder = view.tag as? Holder ?: continue
            updateSongState(holder, row.track)
        }
    }
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 1 else 0
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = rows[position] !is Row.Section
    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
        val row = rows[position]
        if (row is Row.Section) {
            return ((recycled as? TextView) ?: label(14f, accent, true)).apply {
                setTextColor(accent); text = row.title; setPadding(dp(16), dp(18), dp(16), dp(6))
                setAccessibilityHeading(true)
            }
        }
        val h = (recycled?.tag as? Holder) ?: run {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64); setPadding(dp(16), dp(6), dp(16), dp(6)); background = ripple()
                isClickable = true; isFocusable = true
            }
            val art = FrameLayout(context)
            root.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val title = label(15f, foreground, true)
            val detail = label(13f, secondary).apply { setPadding(0, dp(2), 0, 0) }
            texts.addView(title); texts.addView(detail); root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val queueIcons = QueueStatusIcons(context, accent)
            root.addView(queueIcons, LinearLayout.LayoutParams(-2, dp(24)))
            val indicator = PlayingIndicator(context, accent)
            root.addView(indicator, LinearLayout.LayoutParams(dp(20), dp(24)))
            val heart = ImageButton(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ny_heart))
                background = ripple(); setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
            }
            root.addView(heart, LinearLayout.LayoutParams(dp(48), dp(48)))
            val more = ImageButton(context).apply {
                setImageResource(R.drawable.ny_more); imageTintList = ColorStateList.valueOf(secondary)
                background = ripple(); setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
            }
            root.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            val arrow = label(24f, secondary).apply { text = "›"; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            root.addView(arrow, LinearLayout.LayoutParams(dp(32), dp(48)))
            val check = CheckBox(context).apply {
                buttonTintList = ColorStateList.valueOf(accent); isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE
            }
            root.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
            Holder(root, art, title, detail, queueIcons, indicator, heart, more, arrow, check).also { root.tag = it }
        }
        val track = when (row) { is Row.Song -> row.track; is Row.Collection -> row.art; is Row.Section -> null }
        val artKey = ArtKey(track, (row as? Row.Collection)?.title, (row as? Row.Collection)?.artworks)
        if (h.artKey != artKey) {
            h.artKey = artKey; h.artwork.removeAllViews()
            if (row is Row.Collection && row.artworks.isNotEmpty() && collage != null)
                h.artwork.addView(collage.invoke(row.artworks, dp(52)), FrameLayout.LayoutParams(-1, -1))
            else if (track != null) h.artwork.addView(cover(track, dp(52)), FrameLayout.LayoutParams(-1, -1))
            else {
                val initial = (row as? Row.Collection)?.title?.take(1)?.uppercase() ?: "♫"
                h.artwork.addView(label(20f, android.graphics.Color.WHITE, true).apply {
                    text = initial; gravity = Gravity.CENTER; background = PlaceholderArtwork.gradient("collection:${(row as? Row.Collection)?.title.orEmpty()}", dp(12).toFloat())
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
        when (row) {
            is Row.Song -> {
                if (h.root.foreground == null) TrackTouchFeedback.apply(h.root)
                updateSongState(h, row.track)
                h.title.text = row.track.title
                val seconds = row.track.duration.coerceAtLeast(0) / 1000
                val duration = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
                h.detail.text = "${row.track.artist} · $duration"
                val liked = isLiked(row.track)
                h.arrow.visibility = View.GONE
                h.heart.imageTintList = ColorStateList.valueOf(if (liked) accent else secondary)
                h.heart.contentDescription = if (liked) "Unlike ${row.track.title}" else "Like ${row.track.title}"
                h.heart.setOnClickListener { like(row.track) }
                h.more.contentDescription = "Options for ${row.track.title}"
                h.more.setOnClickListener { options(row.track) }
                h.root.setOnClickListener { if (selection() == null) play(row.index) else toggleSelection(row.track) }
                h.root.setOnLongClickListener { if (selection() == null) options(row.track) else toggleSelection(row.track); true }
                h.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Song options"))
                    }
                }
            }
            is Row.Section -> Unit // Returned above; retained for exhaustive matching.
            is Row.Collection -> {
                h.root.foreground = null
                h.indicator.setState(false, false); h.indicator.visibility = View.GONE; h.queueIcons.setStatus(null); h.title.setTextColor(foreground)
                h.title.text = row.title; h.detail.text = row.detail
                h.heart.visibility = View.GONE; h.more.visibility = View.GONE; h.check.visibility = View.GONE; h.arrow.visibility = View.VISIBLE
                h.root.contentDescription = "${row.title}, ${row.detail}${if (row.hold != null) ". Touch and hold for playlist options." else ""}"
                h.root.setOnClickListener { row.click() }
                h.root.setOnLongClickListener(if (row.hold != null) View.OnLongClickListener { row.hold.invoke(); true } else null)
                h.root.isLongClickable = row.hold != null
                h.root.accessibilityDelegate = null
            }
        }
        return h.root
    }
}
