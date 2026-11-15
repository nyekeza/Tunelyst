package com.tunelyst.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/** Updates ExoPlayer's traversal order in-place: audio and timeline remain untouched. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SmartShuffleController(context: Context, private val player: ExoPlayer, private val scanLibrary: (Context) -> List<Track> = Library::scan) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var closed = false
    fun enable(identity: Boolean): ListenableFuture<SessionResult> {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count == 0 || current !in 0 until count) return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val snapshot = (0 until count).map { player.getMediaItemAt(it) }
        val originalUpcoming = QueueTraversal.upcoming(player)
        val request = ++generation
        val result = SettableFuture.create<SessionResult>()
        if (identity) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(IntArray(count) { it }, System.nanoTime()))
            player.shuffleModeEnabled = true
            result.set(SessionResult(SessionResult.RESULT_SUCCESS)); return result
        }
        worker.execute {
            val order = runCatching {
                val (likes, stats) = LibraryDb(app).use { it.favorites() to it.listeningStats() }
                val indexed = snapshot.mapIndexedNotNull { i, item ->
                    val id = item.mediaId.toLongOrNull() ?: return@mapIndexedNotNull null
                    val m = item.mediaMetadata
                    i to Track(id, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.albumTitle?.toString().orEmpty(), -1L, 0L)
                }
                val priority = originalUpcoming.filter { QueuePriority.origin(snapshot[it]) != null }
                val regular = originalUpcoming.filter { QueuePriority.origin(snapshot[it]) == null }
                val regularSet = regular.toHashSet()
                val candidates = indexed.filter { it.first in regularSet }
                val buckets = candidates.groupBy { it.second.id }.mapValues { ArrayDeque(it.value.map { pair -> pair.first }) }
                val currentArtist = indexed.firstOrNull { it.first == current }?.second?.artist
                val shuffled = SmartShuffle.order(candidates.map { it.second }, likes, stats, previousArtists = listOfNotNull(currentArtist), weights = SmartShufflePreferences(app).get())
                    .mapNotNull { buckets[it.id]?.removeFirstOrNull() }
                val known = candidates.mapTo(HashSet()) { it.first }
                val upcoming = shuffled + regular.filter { it !in known }
                // Permission/provider failures must never prevent the existing queue from shuffling.
                val library = runCatching { LibraryFilters(app).apply(scanLibrary(app)) }.getOrDefault(emptyList())
                val context = indexed.map { it.second }
                val limit = ((upcoming.size + 1) / 3).coerceAtLeast(1)
                val recommendations = SmartShuffle.recommendations(context, library, likes, stats, weights = SmartShufflePreferences(app).get())
                    .filter { t -> snapshot.none { it.mediaId == t.id.toString() } }.take(limit)
                val recIndices = recommendations.indices.map { count + it }
                val included = (originalUpcoming + current).toHashSet()
                val prefix = (0 until count).filter { it !in included }
                val afterPriority = if (upcoming.isEmpty()) recIndices else
                    SmartShuffle.interleave(upcoming, recIndices, alreadyPlayedInGroup = (priority.size + 1).coerceAtMost(2))
                val traversal = prefix + listOf(current) + priority + afterPriority
                traversal.toIntArray() to recommendations.map { it.item(suggested = true) }
            }
            main.post {
                if (closed || request != generation || player.currentMediaItemIndex != current || player.mediaItemCount != count ||
                    snapshot.indices.any { player.getMediaItemAt(it) != snapshot[it] }) {
                    result.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                } else order.fold({ (indices, recommendations) ->
                    if (recommendations.isNotEmpty()) player.addMediaItems(recommendations)
                    player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(indices, System.nanoTime()))
                    player.shuffleModeEnabled = true
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }, { result.set(SessionResult(SessionError.ERROR_UNKNOWN)) })
            }
        }
        return result
    }
    private fun shuffledOrder(): List<Int> {
        val timeline = player.currentTimeline
        val result = mutableListOf<Int>(); val seen = HashSet<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != androidx.media3.common.C.INDEX_UNSET && seen.add(index)) {
            result.add(index); index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return result
    }
    /** Reorder the displayed upcoming traversal, not the underlying media items. */
    fun move(from: Int, to: Int, expected: IntArray, expectedIds: List<String>): Boolean {
        if (!player.shuffleModeEnabled || player.mediaItemCount == 0) return false
        val order = shuffledOrder()
        if (order.size != player.mediaItemCount || expected.size != expectedIds.size) return false
        val start = order.indexOf(player.currentMediaItemIndex)
        if (start < 0) return false
        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
        val shown = if (wrap) order.drop(start) + order.take(start) else order.drop(start)
        if (shown != expected.toList() || shown.map { player.getMediaItemAt(it).mediaId } != expectedIds) return false
        val changed = QueueOrder.moveUpcoming(order, player.currentMediaItemIndex, from, to, wrap) ?: return false
        generation++
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(changed.toIntArray(), System.nanoTime()))
        return true
    }
    /** Place explicitly added entries before the regular upcoming queue. */
    fun add(track: Track, playNext: Boolean): Boolean {
        if (player.currentMediaItem == null) return false
        generation++
        val upcoming = QueueTraversal.upcoming(player)
        // A Play next request always becomes immediate; queued requests retain FIFO order.
        val preceding = if (playNext) 0 else upcoming.indexOfLast { QueuePriority.origin(player.getMediaItemAt(it)) != null } + 1
        val item = track.item(queueOrigin = if (playNext) QueuePriority.NEXT else QueuePriority.QUEUED)
        val current = player.currentMediaItemIndex
        val linear = (current + 1 until player.mediaItemCount).toList()
        val linearPriority = if (playNext) 0 else linear.indexOfLast { QueuePriority.origin(player.getMediaItemAt(it)) != null } + 1
        var insertAt = current + 1 + linearPriority
        // With repeat-all, an existing priority block can wrap across the end.
        if (!player.shuffleModeEnabled && preceding > linearPriority) {
            insertAt = if (preceding < upcoming.size) upcoming[preceding] else current
        }
        if (!player.shuffleModeEnabled) {
            player.addMediaItem(insertAt, item)
            return true
        }
        val old = shuffledOrder()
        if (old.size != player.mediaItemCount) return false
        // Inserting into the physical timeline shifts existing indices; remap first.
        val shifted = old.map { if (it >= insertAt) it + 1 else it }
        val currentAfterInsert = if (current >= insertAt) current + 1 else current
        val changed = QueueOrder.insertPriority(shifted, currentAfterInsert, insertAt, preceding, player.repeatMode == Player.REPEAT_MODE_ALL) ?: return false
        player.addMediaItem(insertAt, item)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(changed.toIntArray(), System.nanoTime()))
        return true
    }
    fun disable() { generation++; player.shuffleModeEnabled = false }
    fun release() { closed = true; generation++; worker.shutdown() }
}
