package com.tunelyst.player

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Accepts temporary sender-granted audio without importing files into the library. */
internal class SharedAudioIntents(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val player: MediaController?
        fun hasAccess(): Boolean
        fun requestAccess()
        fun invalidatePendingPlayback()
        fun sendSmartOff()
        fun openPlayer()
        fun toast(message: String)
    }
    private var pending: List<Uri>? = null
    private var launchInFlight = false
    val hasPending: Boolean get() = pending != null
    fun shouldKeepPlaying(current: androidx.media3.common.MediaItem?): Boolean = pending != null || launchInFlight || SharedAudio.isShared(current)
    fun onQueueChanged(player: Player) { if (SharedAudio.isShared(player.currentMediaItem)) launchInFlight = false }
    fun save(out: Bundle) { pending?.let { out.putStringArrayList("shared.pending", ArrayList(it.map(Uri::toString))) } }
    fun restore(state: Bundle) {
        pending = state.getStringArrayList("shared.pending")?.map(Uri::parse)
        launchInFlight = pending != null
    }
    fun receive(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        @Suppress("DEPRECATION")
        val offered = when (action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        val clipUris = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        val uris = (offered + clipUris).filter { it.scheme == "content" || it.scheme == "file" }.distinct()
        if (uris.isEmpty()) { host.toast("No accessible audio file was shared"); return }
        pending = uris; launchInFlight = true
        runCatching {
            val serviceIntent = Intent(host.activity, PlaybackService::class.java).apply {
                clipData = ClipData.newRawUri("Shared audio", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            host.activity.startService(serviceIntent)
        }
        if (!host.hasAccess()) { host.toast("Allow music access to continue with Smart Shuffle after this audio"); host.requestAccess() }
    }
    fun playPending() {
        val p = host.player ?: return
        val uris = pending ?: return
        pending = null; host.invalidatePendingPlayback()
        val items = uris.map { uri ->
            val title = uri.lastPathSegment?.substringAfterLast('/')?.take(100)?.takeIf { it.isNotBlank() } ?: "Shared audio"
            SharedAudio.item(uri, title)
        }
        host.sendSmartOff()
        p.repeatMode = Player.REPEAT_MODE_OFF; p.shuffleModeEnabled = false
        p.setMediaItems(items, 0, 0L); p.prepare(); p.play(); host.openPlayer()
    }
}
