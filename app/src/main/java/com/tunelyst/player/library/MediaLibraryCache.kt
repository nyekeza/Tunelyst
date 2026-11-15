package com.tunelyst.player

import android.content.Context
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Disposable local snapshot; MediaStore remains the source of truth. Call off the UI thread. */
internal class MediaLibraryCache(context: Context) {
    private val file = File(context.applicationContext.cacheDir, "tunelyst-library-v1.cache")
    private val atomic = AtomicFile(file)
    companion object {
        private const val MAGIC = 0x544C4D31
        private const val VERSION = 1
        private const val MAX_ITEMS = 200_000
        private const val MAX_BYTES = 32L * 1024 * 1024
        private val lock = Any()
    }

    fun read(): List<Track>? = synchronized(lock) {
        try {
            // openRead also recovers AtomicFile's previous complete version if needed.
            atomic.openRead().use { raw ->
                if (file.length() > MAX_BYTES) return@synchronized null
                val input = DataInputStream(BufferedInputStream(raw))
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return@synchronized null
                val count = input.readInt()
                if (count !in 0..MAX_ITEMS) return@synchronized null
                val result = ArrayList<Track>(count)
                repeat(count) {
                    result.add(Track(input.readLong(), input.readUTF(), input.readUTF(), input.readUTF(),
                        input.readLong(), input.readLong(), input.readUTF(), input.readUTF(), input.readInt()))
                }
                result
            }
        } catch (_: Exception) { null }
    }

    fun write(tracks: List<Track>) = synchronized(lock) {
        if (tracks.size > MAX_ITEMS) return@synchronized
        var raw: java.io.FileOutputStream? = null
        try {
            raw = atomic.startWrite()
            val output = DataOutputStream(BufferedOutputStream(raw))
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(tracks.size)
            tracks.forEach { track ->
                output.writeLong(track.id); output.writeUTF(track.title); output.writeUTF(track.artist)
                output.writeUTF(track.album); output.writeLong(track.albumId); output.writeLong(track.duration)
                output.writeUTF(track.folder); output.writeUTF(track.volume); output.writeInt(track.trackNumber)
            }
            output.flush()
            if (file.length() > MAX_BYTES) throw java.io.IOException("Library snapshot too large")
            atomic.finishWrite(raw)
        } catch (_: Exception) { if (raw != null) atomic.failWrite(raw) }
    }
}
