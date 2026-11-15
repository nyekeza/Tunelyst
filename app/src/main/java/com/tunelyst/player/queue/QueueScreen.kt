package com.tunelyst.player

import android.content.Context
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Bottom-sheet queue. Rows are recycled and follow the player's actual shuffle order. */
class QueueScreen(
    context: Context,
    private val player: MediaController,
    private val surface: Int,
    private val onSurface: Int,
    private val muted: Int,
    private val accent: Int,
    private val selectedSurface: Int,
    private val selectedText: Int,
    private val onToggleSize: (() -> Unit)? = null,
    private val togglePlayback: (() -> Unit)? = null,
    private val cover: (String?) -> View?
) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, r: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun ripple(color: Int, r: Int = 16) = RippleDrawable(ColorStateList.valueOf(accent and 0x3fffffff), shape(color, r), null)
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun icon(res: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, res)); imageTintList = ColorStateList.valueOf(onSurface)
        background = ripple(surface); setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description; setOnClickListener { click() }
    }
    private val subtitle: TextView
    private val empty: TextView
    private val queueList: ListView
    private val main = Handler(Looper.getMainLooper())
    private var scrollDirection = 0
    private var scrollScheduled = false
    private val scrollTask = object : Runnable {
        override fun run() {
            scrollScheduled = false
            if (scrollDirection != 0 && queueList.canScrollList(scrollDirection)) {
                queueList.scrollListBy(scrollDirection * dp(10))
                scrollScheduled = true; main.postDelayed(this, 45)
            }
        }
    }
    private var playerHeader: QueuePlayerHeader? = null
    private var expandButton: ImageButton? = null
    private val indices = mutableListOf<Int>()
    private var lastEntries: List<Pair<Int, androidx.media3.common.MediaItem>>? = null
    private val adapter = QueueAdapter()
    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(12))
        background = shape(surface, 28).apply { setStroke(dp(1).coerceAtLeast(1), 0x1affffff) }
        addView(View(context).apply { background = shape(muted, 4) }, LayoutParams(dp(42), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        if (togglePlayback != null) {
            playerHeader = QueuePlayerHeader(context, player, onSurface, muted, accent, cover, togglePlayback)
            addView(playerHeader, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(context).apply { orientation = VERTICAL }
        titles.addView(text("Up next", 23f, onSurface, true))
        subtitle = text("", 13f, muted).apply { setPadding(0, dp(4), 0, 0) }
        titles.addView(subtitle)
        heading.addView(titles, LayoutParams(0, -2, 1f))
        val tools = icon(R.drawable.ny_more, "Queue tools") { }
        tools.setOnClickListener { QueueTools.show(context, tools, player) }
        heading.addView(tools, LayoutParams(dp(48), dp(48)))
        if (onToggleSize != null) {
            expandButton = icon(R.drawable.ny_expand_up, "Expand queue", onToggleSize)
            heading.addView(expandButton, LayoutParams(dp(48), dp(48)))
        }
        addView(heading, LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        queueList = ListView(context).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(2)
            setPadding(0, 0, 0, dp(8)); clipToPadding = false; isVerticalScrollBarEnabled = false
            isNestedScrollingEnabled = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            this.adapter = this@QueueScreen.adapter
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is DragItem
                    DragEvent.ACTION_DRAG_LOCATION -> { updateAutoScroll(event.y); true }
                    DragEvent.ACTION_DROP -> {
                        stopAutoScroll()
                        val dragged = event.localState as? DragItem
                        val position = pointToPosition(event.x.toInt(), event.y.toInt())
                        if (dragged != null && position in indices.indices) move(dragged, indices[position])
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { stopAutoScroll(); true }
                    else -> true
                }
            }
        }
        empty = text("", 15f, muted).apply { gravity = Gravity.CENTER }
        val container = FrameLayout(context)
        container.addView(queueList, FrameLayout.LayoutParams(-1, -1)); container.addView(empty, FrameLayout.LayoutParams(-1, -1)); queueList.emptyView = empty
        addView(container, LayoutParams(-1, 0, 1f))
        refresh()
    }
    fun setExpanded(expanded: Boolean) {
        expandButton?.setImageResource(if (expanded) R.drawable.ny_expand_down else R.drawable.ny_expand_up)
        expandButton?.contentDescription = if (expanded) "Show compact queue" else "Expand queue"
    }
    /** Keep the list within the visible portion of the sheet. */
    fun setBottomOcclusion(hidden: Int) {
        val bottom = dp(12) + hidden.coerceAtLeast(0)
        if (paddingBottom != bottom) setPadding(paddingLeft, paddingTop, paddingRight, bottom)
    }
    fun refresh(updateQueue: Boolean = true) {
        playerHeader?.refresh()
        if (!updateQueue) return
        val upcoming = QueueTraversal.upcoming(player)
        val entries = upcoming.map { it to player.getMediaItemAt(it) }
        val changed = entries != lastEntries
        if (changed) { indices.clear(); indices.addAll(upcoming); lastEntries = entries }
        subtitle.text = when {
            player.currentMediaItem == null -> "Your local music"
            indices.isEmpty() -> "No upcoming songs"
            else -> "${indices.size} upcoming · hold and drag to reorder"
        }
        empty.text = if (player.currentMediaItem == null) "Nothing in your queue yet.\nChoose a song to get started."
            else "No songs up next.\nOpen a song's options to add it to the queue."
        if (changed) adapter.notifyDataSetChanged()
    }
    private data class DragItem(val index: Int, val current: Int, val indices: List<Int>, val ids: List<String>)
    private fun snapshot(index: Int): DragItem? {
        val current = player.currentMediaItemIndex
        val shown = listOf(current) + indices
        if (index !in indices || current !in 0 until player.mediaItemCount || shown.any { it !in 0 until player.mediaItemCount }) return null
        return DragItem(index, current, shown, shown.map { player.getMediaItemAt(it).mediaId })
    }
    private fun canReorder(index: Int): Boolean {
        if (index !in indices) {
            Toast.makeText(context, "Only upcoming songs can be moved", Toast.LENGTH_SHORT).show(); return false
        }
        return true
    }
    private fun move(dragged: DragItem, to: Int) {
        val shown = listOf(player.currentMediaItemIndex) + indices
        if (dragged.current != player.currentMediaItemIndex || shown != dragged.indices ||
            shown.any { it !in 0 until player.mediaItemCount } || shown.map { player.getMediaItemAt(it).mediaId } != dragged.ids) {
            Toast.makeText(context, "Queue changed; try moving again", Toast.LENGTH_SHORT).show(); return
        }
        if (!canReorder(dragged.index) || to !in indices || dragged.index == to) return
        if (!player.shuffleModeEnabled) {
            val destination = QueueOrder.unshuffledDestination(indices, dragged.index, to) ?: return
            player.moveMediaItem(dragged.index, destination); return
        }
        val args = android.os.Bundle().apply {
            putString(PlaybackService.SMART_SHUFFLE_MODE, "move"); putInt("from", dragged.index); putInt("to", to)
            putIntArray("indices", dragged.indices.toIntArray())
            putStringArrayList("ids", ArrayList(dragged.ids))
        }
        val future = player.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.SMART_SHUFFLE_COMMAND, android.os.Bundle.EMPTY), args)
        future.addListener({
            val ok = runCatching { future.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            if (!ok && isAttachedToWindow) Toast.makeText(context, "Queue changed; try moving again", Toast.LENGTH_SHORT).show()
        }, ContextCompat.getMainExecutor(context))
    }
    private fun updateAutoScroll(y: Float) {
        val edge = minOf(dp(56), queueList.height / 3)
        val direction = when { edge <= 0 -> 0; y < edge -> -1; y > queueList.height - edge -> 1; else -> 0 }
        if (direction == scrollDirection && scrollScheduled) return
        scrollDirection = direction
        if (direction == 0) { main.removeCallbacks(scrollTask); scrollScheduled = false }
        else if (!scrollScheduled) { scrollScheduled = true; main.post(scrollTask) }
    }
    private fun stopAutoScroll() { scrollDirection = 0; scrollScheduled = false; main.removeCallbacks(scrollTask) }
    override fun onDetachedFromWindow() { stopAutoScroll(); super.onDetachedFromWindow() }
    private inner class Holder(val root: LinearLayout, val artwork: FrameLayout, val title: TextView, val detail: TextView, val more: ImageButton, var artworkId: String? = null)
    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount() = indices.size
        override fun getItem(position: Int) = player.getMediaItemAt(indices[position])
        override fun getItemId(position: Int) = indices[position].toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val h = (recycled?.tag as? Holder) ?: run {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); minimumHeight = dp(64); TrackTouchFeedback.apply(this, 16) }
                val art = FrameLayout(context)
                row.addView(art, LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
                val labels = LinearLayout(context).apply { orientation = VERTICAL }
                val title = text("", 15f, onSurface, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val detail = text("", 13f, muted).apply { setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                labels.addView(title); labels.addView(detail); row.addView(labels, LayoutParams(0, -2, 1f))
                val more = icon(R.drawable.ny_more, "Song options") {}
                row.addView(more, LayoutParams(dp(48), dp(48)))
                Holder(row, art, title, detail, more).also { row.tag = it }
            }
            val itemIndex = indices.getOrNull(position) ?: return h.root
            if (itemIndex >= player.mediaItemCount) return h.root
            val item = player.getMediaItemAt(itemIndex)
            h.root.background = ripple(surface, 16)
            if (h.artworkId != item.mediaId) {
                h.artworkId = item.mediaId; h.artwork.removeAllViews()
                val image = cover(item.mediaId)
                if (image != null) h.artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
                else h.artwork.addView(android.widget.ImageView(context).apply {
                    background = PlaceholderArtwork.gradient("queue:${item.mediaId}", dp(12).toFloat())
                    setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(Color.WHITE)
                    setPadding(dp(15), dp(15), dp(15), dp(15)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(-1, -1))
            }
            h.title.text = item.mediaMetadata.title ?: "Untitled"
            val artist = item.mediaMetadata.artist ?: "Unknown artist"
            val suggested = if (item.mediaMetadata.extras?.getBoolean("tunelyst.suggested") == true) "Suggested" else null
            h.detail.text = listOfNotNull(artist.toString(), suggested).joinToString(" · ")
            h.root.contentDescription = "${h.title.text}, ${h.detail.text}. Tap to play; hold and drag to reorder, or use song options."
            h.root.setOnClickListener { if (itemIndex < player.mediaItemCount && player.getMediaItemAt(itemIndex).mediaId == item.mediaId) { player.seekToDefaultPosition(itemIndex); player.play() } }
            fun showOptions() {
                val captured = snapshot(itemIndex) ?: return
                MaterialAlertDialogBuilder(context).setTitle(item.mediaMetadata.title ?: "Song")
                    .setItems(arrayOf("Move up", "Move down", "Remove from queue")) { _, which ->
                        val currentPosition = indices.indexOf(itemIndex)
                        if (which == 2) {
                            if (player.currentMediaItemIndex == captured.current && currentPosition >= 0 && itemIndex < player.mediaItemCount && player.getMediaItemAt(itemIndex).mediaId == item.mediaId) player.removeMediaItem(itemIndex)
                        } else {
                            val targetPosition = if (which == 0) currentPosition - 1 else currentPosition + 1
                            if (targetPosition in indices.indices) move(captured, indices[targetPosition])
                            else Toast.makeText(context, "Can't move further in that direction", Toast.LENGTH_SHORT).show()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
            h.more.contentDescription = "Options for ${h.title.text}"
            h.more.setOnClickListener { showOptions() }
            h.root.setOnLongClickListener { view ->
                val captured = if (canReorder(itemIndex)) snapshot(itemIndex) else null
                captured != null && view.startDragAndDrop(ClipData.newPlainText("queue item", ""), View.DragShadowBuilder(view), captured, 0)
            }
            h.root.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is DragItem
                    DragEvent.ACTION_DRAG_ENTERED -> { view.background = ripple(selectedSurface, 16); true }
                    DragEvent.ACTION_DRAG_LOCATION -> { updateAutoScroll(view.top + event.y); true }
                    DragEvent.ACTION_DRAG_EXITED -> { view.background = ripple(surface, 16); true }
                    DragEvent.ACTION_DROP -> {
                        stopAutoScroll(); view.background = ripple(surface, 16)
                        val moving = event.localState as? DragItem
                        if (moving != null) move(moving, itemIndex)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { stopAutoScroll(); view.background = ripple(surface, 16); true }
                    else -> true
                }
            }
            return h.root
        }
    }
}
