package com.tunelyst.player

import java.util.concurrent.Executor

/** Coordinates debounced, background local searches against the current library snapshot. */
internal class SearchCoordinator(
    private val worker: Executor,
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val currentIndex: () -> LibraryIndex,
    private val currentQuery: () -> String,
    private val destroyed: () -> Boolean,
    private val isSearchScreen: () -> Boolean,
    private val canRenderResults: () -> Boolean,
    private val render: () -> Unit
) {
    var results: List<Track> = emptyList()
        private set
    var searchedFor: String = ""
        private set
    private var generation = 0
    private var pending: Runnable? = null
    private var closed = false

    /** Called after the activity has updated the visible text and query. */
    fun onTextChanged() {
        if (closed) return
        pending?.let(remove)
        searchedFor = ""
        val task = Runnable {
            if (!closed && !destroyed() && isSearchScreen()) {
                if (currentQuery().isNotBlank()) schedule(currentQuery())
                render()
            }
        }
        pending = task
        postDelayed(task, 180)
    }

    /** Also used after the library index changes during a scan. */
    fun schedule(value: String) {
        if (closed) return
        val q = value.trim()
        val index = currentIndex()
        val request = ++generation
        if (q.isBlank()) { results = emptyList(); searchedFor = ""; return }
        worker.execute {
            val matches = index.search(q)
            post(Runnable {
                if (!closed && !destroyed() && request == generation && index === currentIndex() && currentQuery().trim() == q) {
                    results = matches; searchedFor = q
                    if (canRenderResults()) render()
                }
            })
        }
    }

    fun close() {
        closed = true
        pending?.let(remove)
        pending = null
    }
}
