package com.tunelyst.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Compact playback controls stay visible while the queue is expanded. */
internal class QueuePlayerHeader(
    context: Context,
    private val player: MediaController,
    private val textColor: Int,
    private val secondary: Int,
    private val accent: Int,
    private val cover: (String?) -> View?,
    private val togglePlayback: () -> Unit
) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun control(icon: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, icon)); imageTintList = ColorStateList.valueOf(textColor)
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape(Color.TRANSPARENT, 24), null)
        setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = description
        setOnClickListener { action() }
    }
    private val art = FrameLayout(context)
    private val title = TextView(context).apply { textSize = 15f; setTextColor(textColor); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val eyebrow = TextView(context).apply { textSize = 10f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text = "NOW PLAYING" }
    private val artist = TextView(context).apply { textSize = 13f; setTextColor(secondary); setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val previous = control(R.drawable.ny_previous, "Previous song") { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
    private val play = control(R.drawable.ny_play, "Play") { togglePlayback() }
    private val next = control(R.drawable.ny_next, "Next song") { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    private var artKey: String? = null
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
        art.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(art, LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(eyebrow); labels.addView(title); labels.addView(artist); addView(labels, LayoutParams(0, -2, 1f))
        addView(previous, LayoutParams(dp(48), dp(48)))
        addView(play, LayoutParams(dp(48), dp(48)))
        addView(next, LayoutParams(dp(48), dp(48)))
        refresh()
    }
    fun refresh() {
        val item = player.currentMediaItem
        eyebrow.text = if (item == null) "PLAYER" else if (player.isPlaying) "NOW PLAYING" else "PAUSED"
        title.text = item?.mediaMetadata?.title ?: "Nothing playing"
        artist.text = item?.mediaMetadata?.artist ?: "Choose a song from your library"
        val key = item?.mediaId ?: ""
        if (artKey != key) {
            artKey = key; art.removeAllViews()
            val image = cover(item?.mediaId) ?: ImageView(context).apply {
                background = PlaceholderArtwork.gradient("queue-header:$key", dp(12).toFloat())
                setImageResource(R.drawable.ny_music); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(13), dp(13), dp(13), dp(13))
            }
            art.addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        play.setImageResource(if (player.isPlaying) R.drawable.ny_pause else R.drawable.ny_play)
        play.contentDescription = if (player.isPlaying) "Pause" else "Play"
        previous.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && player.hasPreviousMediaItem()
        next.isEnabled = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && player.hasNextMediaItem()
        previous.alpha = if (previous.isEnabled) 1f else 0.4f
        next.alpha = if (next.isEnabled) 1f else 0.4f
    }
}
