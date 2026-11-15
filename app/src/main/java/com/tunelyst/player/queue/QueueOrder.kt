package com.tunelyst.player

/** Operations on a permutation of timeline indices, without replacing the queue. */
internal object QueueOrder {
    /** Destination for a single timeline move, including repeat-all's wrap across the current item. */
    fun unshuffledDestination(upcoming: List<Int>, from: Int, to: Int): Int? {
        val source = upcoming.indexOf(from); val target = upcoming.indexOf(to)
        if (source < 0 || target < 0 || upcoming.distinct().size != upcoming.size) return null
        if (source == target) return from
        return if (source < target) to + if (from > to) 1 else 0
            else to - if (from < to) 1 else 0
    }
    fun moveUpcoming(order: List<Int>, current: Int, from: Int, to: Int, wrap: Boolean): List<Int>? {
        if (order.distinct().size != order.size) return null
        val currentPosition = order.indexOf(current)
        if (currentPosition < 0) return null
        val prefix = if (wrap) emptyList() else order.take(currentPosition)
        val shown = if (wrap) (order.drop(currentPosition) + order.take(currentPosition)).toMutableList() else order.drop(currentPosition).toMutableList()
        val source = shown.indexOf(from); val target = shown.indexOf(to)
        if (source <= 0 || target <= 0) return null
        if (source != target) { shown.removeAt(source); shown.add(target, from) }
        return prefix + shown
    }
    fun insert(order: List<Int>, current: Int, addedIndex: Int, playNext: Boolean, wrap: Boolean): List<Int>? {
        if (addedIndex in order || order.distinct().size != order.size) return null
        val currentPosition = order.indexOf(current)
        if (currentPosition < 0) return null
        val oriented = if (wrap) order.drop(currentPosition) + order.take(currentPosition) else order
        val cursor = oriented.indexOf(current)
        return oriented.toMutableList().apply { add(if (playNext) cursor + 1 else size, addedIndex) }
    }
    /** Inserts immediately after the current entry, or after the existing priority block. */
    fun insertPriority(order: List<Int>, current: Int, addedIndex: Int, afterPriority: Int, wrap: Boolean): List<Int>? {
        if (addedIndex in order || order.distinct().size != order.size || afterPriority < 0) return null
        val start = order.indexOf(current)
        if (start < 0) return null
        val oriented = if (wrap) order.drop(start) + order.take(start) else order
        val cursor = oriented.indexOf(current)
        if (cursor + afterPriority >= oriented.size) return null
        return oriented.toMutableList().apply { add(cursor + 1 + afterPriority, addedIndex) }
    }
}
