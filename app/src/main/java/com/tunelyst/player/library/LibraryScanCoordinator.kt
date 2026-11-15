package com.tunelyst.player

import android.content.Context
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Coalesce only simultaneous MediaStore scans; completed results are never cached here. */
internal object LibraryScanCoordinator {
    private val lock = Any()
    private var inFlight: FutureTask<List<Track>>? = null
    fun scan(context: Context): List<Track> = run { Library.scan(context.applicationContext) }
    internal fun run(query: () -> List<Track>): List<Track> {
        var owner = false
        val task = synchronized(lock) {
            inFlight?.takeUnless { it.isDone } ?: FutureTask(query).also { inFlight = it; owner = true }
        }
        if (owner) task.run()
        try { return task.get() }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
        finally { if (task.isDone) synchronized(lock) { if (inFlight === task) inFlight = null } }
    }
}
