package com.tunelyst.player

/** Queue order and summary shared by the collapsed preview and the expanded queue. */
internal object QueuePreviewModel {
    /** Traverse at most one cycle, excluding the current item even when repeat-all wraps. */
    fun upcoming(count: Int, current: Int, next: (Int) -> Int): List<Int> {
        if (count <= 0 || current !in 0 until count) return emptyList()
        val result = ArrayList<Int>()
        val seen = HashSet<Int>()
        var index = current
        while (index in 0 until count && seen.add(index)) {
            if (index != current) result.add(index)
            index = next(index)
        }
        return result
    }
    data class Summary(val heading: String, val detail: String)
    fun summary(connected: Boolean, upcomingCount: Int, nextTitle: String?, repeating: Boolean): Summary {
        if (!connected) return Summary("Up next", "Connecting to player…")
        val count = upcomingCount.coerceAtLeast(0)
        val heading = "Up next · $count ${if (count == 1) "track" else "tracks"}"
        val title = nextTitle?.trim()?.takeIf { it.isNotEmpty() }
        val detail = if (title == null) "No upcoming songs" else if (repeating) "Repeating: $title" else "Next: $title"
        return Summary(heading, detail)
    }
    data class Presentation(val heading: String, val title: String, val detail: String, val accessibility: String)
    fun presentation(connected: Boolean, upcomingCount: Int, nextTitle: String?, nextArtist: String?, repeating: Boolean): Presentation {
        val title = nextTitle?.trim()?.takeIf { it.isNotEmpty() }
        val artist = nextArtist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown artist"
        val summary = summary(connected, upcomingCount, title, repeating)
        val line = if (title == null) "Tap to view your queue" else if (repeating) "Repeating · $artist" else artist
        val accessibleArtist = if (title == null) "" else ", $artist"
        return Presentation(summary.heading.uppercase(java.util.Locale.ROOT),
            title ?: if (connected) "No upcoming songs" else "Connecting to player…", line,
            "${summary.heading}. ${summary.detail}$accessibleArtist. Tap or swipe up to open queue")
    }

}
