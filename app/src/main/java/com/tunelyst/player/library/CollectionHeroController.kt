package com.tunelyst.player

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Collection artwork, play/pause control and immersive inset adjustments. */
internal class CollectionHeroController(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val content: LinearLayout
        val player: MediaController?
        val groupIds: List<Long>?
        val tileSurface: Int
        val accent: Int
        val onAccent: Int
        val insetTop: Int
        fun dp(value: Int): Int
        fun col(): LinearLayout
        fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView
        fun iconButton(icon: Int, description: String, fill: Int, tint: Int, action: () -> Unit): ImageButton
        fun collage(list: List<Track>, size: Int): View
        fun applyGradient(track: Track?, target: View)
        fun cancelPendingPlayback()
        fun play(list: List<Track>)
        fun toast(message: String)
    }
    var view: View? = null
        private set
    private var back: View? = null
    var button: ImageButton? = null
        private set
    private var ids: Set<Long> = emptySet()
    private var title = ""
    fun reset() { view = null; back = null; button = null; ids = emptySet(); title = "" }
    private fun action(p: Player?) = CollectionPlaybackState.action(ids, p?.currentMediaItem?.mediaId?.toLongOrNull(),
        p?.isPlaying == true, p?.playWhenReady == true, p?.playbackState ?: Player.STATE_IDLE)
    fun render(name: String, list: List<Track>, onBack: () -> Unit) = with(host) {
        val widthDp = activity.resources.configuration.screenWidthDp.coerceAtLeast(240)
        val height = ImmersiveHeroSize.height(dp(ImmersiveHeroSize.BASE_DP), content.paddingTop)
        val hero = FrameLayout(activity).apply { setBackgroundColor(tileSurface) }; view = hero
        if (list.isNotEmpty()) hero.addView(collage(list, dp(widthDp)), FrameLayout.LayoutParams(-1, -1))
        val tint = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        applyGradient(list.firstOrNull(), tint); hero.addView(tint, FrameLayout.LayoutParams(-1, -1))
        val backButton = iconButton(R.drawable.ny_back, "Back to previous page", 0xb8000000.toInt(), Color.WHITE, onBack)
        back = backButton
        hero.addView(backButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { marginStart = dp(16); topMargin = insetTop + dp(12) })
        val info = col()
        info.addView(label("YOUR COLLECTION", 10f, Color.WHITE, true).apply { letterSpacing = 0.1f })
        info.addView(label(name, 27f, Color.WHITE, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, dp(5)) })
        info.addView(label("${list.size} songs · on your device", 13f, Color.WHITE).apply { maxLines = 2 })
        hero.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(18), 0, dp(90), dp(20)) })
        if (list.isNotEmpty()) {
            ids = (groupIds ?: list.map { it.id }).toHashSet(); title = name
            val playButton = iconButton(R.drawable.ny_play, "Play $name", accent, onAccent) {
                val p = player
                if (p == null) toast("Player connecting…") else when (action(p)) {
                    CollectionPlaybackState.Action.PAUSE -> { cancelPendingPlayback(); p.pause() }
                    CollectionPlaybackState.Action.RESUME -> {
                        cancelPendingPlayback()
                        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                        if (p.playbackState == Player.STATE_IDLE) p.prepare()
                        p.play()
                    }
                    CollectionPlaybackState.Action.START -> play(list)
                }
                updateButton()
            }
            button = playButton
            hero.addView(playButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(18); bottomMargin = dp(18) })
            updateButton()
        }
        content.addView(hero, LinearLayout.LayoutParams(-1, height).apply { leftMargin = -dp(16); rightMargin = -dp(16); bottomMargin = dp(14) })
        updateInsets()
    }
    fun updateButton() {
        val button = button ?: return
        val action = action(host.player); val pause = action == CollectionPlaybackState.Action.PAUSE
        if (button.isSelected != pause) { button.setImageResource(if (pause) R.drawable.ny_pause else R.drawable.ny_play); button.isSelected = pause }
        button.contentDescription = "${if (pause) "Pause" else if (action == CollectionPlaybackState.Action.RESUME) "Resume" else "Play"} $title"
    }
    fun updateInsets(): Unit = with(host) {
        val hero = view ?: return@with
        val params = hero.layoutParams as? LinearLayout.LayoutParams ?: return@with
        val newTop = -content.paddingTop
        val newHeight = ImmersiveHeroSize.height(dp(ImmersiveHeroSize.BASE_DP), content.paddingTop)
        if (params.topMargin != newTop || params.height != newHeight) { params.topMargin = newTop; params.height = newHeight; hero.layoutParams = params }
        back?.let { button ->
            val layout = button.layoutParams as? FrameLayout.LayoutParams ?: return@let
            val top = insetTop + dp(12)
            if (layout.topMargin != top) { layout.topMargin = top; button.layoutParams = layout }
        }
        Unit
    }
}
