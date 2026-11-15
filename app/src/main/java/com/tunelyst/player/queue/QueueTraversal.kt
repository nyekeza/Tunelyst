package com.tunelyst.player

import androidx.media3.common.Player

/** Mirrors the expanded queue: upcoming items in timeline/shuffle order, one cycle at most. */
internal object QueueTraversal {
    fun upcoming(player: Player): List<Int> {
        val timeline = player.currentTimeline
        val mode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        return QueuePreviewModel.upcoming(timeline.windowCount, player.currentMediaItemIndex) { index ->
            timeline.getNextWindowIndex(index, mode, player.shuffleModeEnabled)
        }
    }
}
