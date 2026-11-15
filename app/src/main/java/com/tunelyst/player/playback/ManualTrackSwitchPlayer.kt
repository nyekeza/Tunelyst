package com.tunelyst.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.ListenableFuture

/** Intercept actual item changes from MediaController, notification and other clients. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ManualTrackSwitchPlayer(
    private val base: ExoPlayer,
    private val beforeNavigation: () -> Unit,
    private val switch: (Int, String, () -> ListenableFuture<*>) -> ListenableFuture<*>
) : ForwardingSimpleBasePlayer(base) {
    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        // Media3 otherwise freezes the original edge-of-queue command set in its
        // placeholder while a switch is pending, so a rapid opposite skip is lost.
        // Timeline traversal still makes commands at an actual boundary no-ops.
        if (base.mediaItemCount < 2 || !state.availableCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) return state
        val commands = state.availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // A prior deferred next/previous may have advanced the facade's predicted
        // index without moving the base yet. Commit it before resolving another.
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) beforeNavigation()
        if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex !in 0 until base.mediaItemCount || mediaItemIndex == base.currentMediaItemIndex) {
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        val id = base.getMediaItemAt(mediaItemIndex).mediaId
        return switch(mediaItemIndex, id) { super.handleSeek(mediaItemIndex, positionMs, seekCommand) }
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        beforeNavigation() // Finish a prior deferred command before replacing its queue.
        // An unset index in the forwarding API means reset to the first item.
        val target = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        if (target !in mediaItems.indices || base.currentMediaItem == null || mediaItems[target].mediaId == base.currentMediaItem?.mediaId) {
            return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        }
        return switch(target, mediaItems[target].mediaId) { super.handleSetMediaItems(mediaItems, startIndex, startPositionMs) }
    }
}
