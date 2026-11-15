package com.tunelyst.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Per-entry markers, not persisted separately from the actual playback queue. */
internal object QueuePriority {
    const val KEY = "tunelyst.queue_origin"
    const val NEXT = "next"
    const val QUEUED = "queued"
    fun origin(item: MediaItem): String? = item.mediaMetadata.extras?.getString(KEY)?.takeIf { it == NEXT || it == QUEUED }
    fun label(origin: String?): String? = when (origin) { NEXT -> "Play next"; QUEUED -> "In queue"; else -> null }
    fun statuses(player: Player?): Map<Long, String> {
        if (player == null || player.currentMediaItem == null) return emptyMap()
        val found = mutableMapOf<Long, MutableSet<String>>()
        for (index in QueueTraversal.upcoming(player)) {
            val item = player.getMediaItemAt(index)
            val id = item.mediaId.toLongOrNull() ?: continue
            val origin = origin(item) ?: continue
            found.getOrPut(id) { mutableSetOf() }.add(origin)
        }
        return found.mapValues { (_, origins) ->
            listOfNotNull(if (NEXT in origins) "Play next" else null, if (QUEUED in origins) "In queue" else null).joinToString(" · ")
        }
    }
}
