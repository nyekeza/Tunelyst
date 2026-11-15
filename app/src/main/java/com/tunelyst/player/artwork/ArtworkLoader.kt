package com.tunelyst.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** Visible-only loading and bounded artwork caching. */
internal class ArtworkLoader(private val context: Context, private val worker: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private val artworkCacheBytes = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
    internal val coverCache = object : LruCache<String, Bitmap>(artworkCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private data class WaitingImage(val view: WeakReference<ImageView>, val clearOnMissing: Boolean)
    private val artworkWaiting = mutableMapOf<String, MutableList<WaitingImage>>()
    private val missingArtwork = mutableMapOf<Long, Long>()
    private var artworkGeneration = 0
    private var closed = false
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun invalidate() { artworkGeneration++; missingArtwork.clear(); coverCache.evictAll() }
    fun close() { closed = true; artworkWaiting.clear(); coverCache.evictAll() }
    fun loadArtwork(track: Track, image: ImageView, size: Int, clearOnMissing: Boolean = false) {
        val bucket = when { size <= dp(64) -> 64; size <= dp(128) -> 128; size <= dp(256) -> 256; else -> 512 }
        val target = dp(bucket)
        val albumKey = if (track.albumId >= 0) "album:${track.albumId}:$bucket" else null
        val trackKey = "track:${track.id}:$bucket"
        image.tag = trackKey
        fun cached(): Bitmap? = albumKey?.let { coverCache.get(it) } ?: coverCache.get(trackKey)
        cached()?.let { image.setImageBitmap(it); image.visibility = View.VISIBLE; return }
        if ((missingArtwork[track.id] ?: 0L) > android.os.SystemClock.elapsedRealtime()) {
            if (clearOnMissing) image.visibility = View.INVISIBLE
            return
        }

        // Defer work until the row/card enters a visible viewport. This stops
        // off-screen carousel pages from evicting visible covers during a return.
        val observer = object : View.OnAttachStateChangeListener, android.view.ViewTreeObserver.OnScrollChangedListener,
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            private var watched: android.view.ViewTreeObserver? = null
            private fun unwatch() {
                watched?.takeIf { it.isAlive }?.let { it.removeOnScrollChangedListener(this); it.removeOnGlobalLayoutListener(this) }
                watched = null
            }
            private fun maybeLoad() {
                val frame = image.parent as? View ?: return
                val visible = android.graphics.Rect()
                if (!frame.isAttachedToWindow || !frame.getGlobalVisibleRect(visible) || visible.isEmpty) return
                unwatch(); image.removeOnAttachStateChangeListener(this)
                if (image.tag != trackKey) return
                cached()?.let { image.setImageBitmap(it); image.visibility = View.VISIBLE; return }
                val generation = artworkGeneration
                val requestKey = "$generation:$trackKey"
                val pending = artworkWaiting[requestKey]
                if (pending != null) { pending.add(WaitingImage(WeakReference(image), clearOnMissing)); return }
                artworkWaiting[requestKey] = mutableListOf(WaitingImage(WeakReference(image), clearOnMissing))
                worker.execute {
                    val result = try { ArtworkDecoder.decode(context.applicationContext, track, target) } catch (_: Exception) { null }
                    main.post {
                        val waiting = artworkWaiting.remove(requestKey).orEmpty()
                        if (!closed && generation == artworkGeneration) {
                            if (result == null) {
                                missingArtwork[track.id] = android.os.SystemClock.elapsedRealtime() + 30_000L
                                waiting.forEach { item -> item.view.get()?.let { view ->
                                    if (item.clearOnMissing && view.tag == trackKey) view.visibility = View.INVISIBLE
                                } }
                            } else {
                                missingArtwork.remove(track.id)
                                val key = if (result.albumWide && albumKey != null) albumKey else trackKey
                                coverCache.put(key, result.bitmap)
                                waiting.forEach { item -> item.view.get()?.let { view ->
                                    if (view.tag == trackKey) { view.setImageBitmap(result.bitmap); view.visibility = View.VISIBLE; view.alpha = 1f }
                                } }
                            }
                        }
                    }
                }
            }
            override fun onViewAttachedToWindow(v: View) {
                unwatch(); watched = v.viewTreeObserver
                watched?.addOnScrollChangedListener(this); watched?.addOnGlobalLayoutListener(this)
                v.post { maybeLoad() }
            }
            override fun onViewDetachedFromWindow(v: View) { unwatch() }
            override fun onScrollChanged() = maybeLoad()
            override fun onGlobalLayout() = maybeLoad()
        }
        image.addOnAttachStateChangeListener(observer)
        if (image.isAttachedToWindow) observer.onViewAttachedToWindow(image)
    }
}
