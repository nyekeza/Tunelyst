package com.tunelyst.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import java.util.concurrent.Executor

/** Builds playback lists off the UI thread, rejecting results superseded by user actions. */
internal class PlaybackStarter(private val host: Host) {
    interface Host {
        val context: Context
        val io: Executor
        val main: Handler
        val player: MediaController?
        val tracks: List<Track>
        val favorites: Set<Long>
        val screen: String
        val groupTitle: String?
        val groupIds: List<Long>?
        fun nextRequest(): Int
        fun isCurrentRequest(request: Int, player: MediaController): Boolean
        fun sendSmartCommand(mode: String, announce: Boolean)
        fun toggleSmartShuffle()
        fun saveSearchedSong(id: Long)
        fun openPlayer()
        fun toast(message: String)
    }
    fun playList(list: List<Track>, index: Int, searchSelection: Boolean = true, sourceOverride: String? = null, openPlayerOnStart: Boolean = true): Unit = with(host) {
        val p = player ?: run { toast("Player connecting, try again"); return }
        if (index !in list.indices) return
        val fromSearch = screen == "Search" && groupIds == null && searchSelection
        val source = sourceOverride ?: groupTitle ?: when (screen) { "Search" -> "Search"; "Liked" -> "Liked songs"; "Library" -> "Your library"; else -> "On your device" }
        val chosen = list[index]
        val request = nextRequest()
        val selection = list.toList()
        val localLibrary = if (fromSearch) tracks.toList() else emptyList()
        val liked = favorites.toSet()
        io.execute {
            val result = runCatching {
                if (fromSearch) {
                    val stats = LibraryDb(context.applicationContext).use { it.listeningStats() }
                    SmartShuffle.afterSelected(chosen, localLibrary, liked, stats, weights = SmartShufflePreferences(context).get()).map { it.item(source) }
                } else selection.map { it.item(source) }
            }
            main.post {
                if (isCurrentRequest(request, p)) {
                    result.onSuccess { items ->
                        sendSmartCommand("off", announce = false)
                        if (fromSearch) p.repeatMode = Player.REPEAT_MODE_OFF
                        p.setMediaItems(items, if (fromSearch) 0 else index, 0L)
                        if (fromSearch) sendSmartCommand("identity", announce = false)
                        p.prepare(); p.play()
                        if (fromSearch) saveSearchedSong(chosen.id)
                        if (openPlayerOnStart) openPlayer()
                    }.onFailure { toast("Couldn't prepare playback") }
                }
            }
        }
    }
    fun startSmartShuffle(list: List<Track>): Unit = with(host) {
        val p = player ?: run { toast("Player connecting, try again"); return }
        if (list.isEmpty()) { toast("No tracks to shuffle"); return }
        if (p.currentMediaItem != null && screen == "Home" && groupIds == null) { toggleSmartShuffle(); return }
        val request = nextRequest()
        val source = groupTitle ?: if (screen == "Home") "Your library" else screen
        val selected = list.toList()
        val localLibrary = tracks.toList()
        val liked = favorites.toSet()
        toast("Preparing Smart Shuffle…")
        io.execute {
            val result = runCatching {
                val history = LibraryDb(context.applicationContext).use { it.listeningStats() }
                val original = SmartShuffle.order(selected, liked, history, weights = SmartShufflePreferences(context).get())
                val suggested = SmartShuffle.recommendations(selected, localLibrary, liked, history, weights = SmartShufflePreferences(context).get())
                val suggestedIds = suggested.mapTo(HashSet()) { it.id }
                SmartShuffle.interleave(original, suggested).map { it.item(source, it.id in suggestedIds) }
            }
            main.post {
                if (isCurrentRequest(request, p)) {
                    result.onSuccess { items ->
                        sendSmartCommand("off", announce = false)
                        p.setMediaItems(items, 0, 0L)
                        sendSmartCommand("identity", announce = false)
                        p.prepare(); p.play(); openPlayer()
                        toast("Smart Shuffle is playing")
                    }.onFailure { toast("Couldn't prepare Smart Shuffle") }
                }
            }
        }
    }
}
