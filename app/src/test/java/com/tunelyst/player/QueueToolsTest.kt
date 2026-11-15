package com.tunelyst.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class QueueToolsTest {
 private fun item(id: Long)=MediaItem.Builder().setMediaId(id.toString()).setUri("file:///$id.mp3").build()
 @Test fun clearUpcomingKeepsCurrentAndRejectsStaleSnapshot() {
  val p=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build();p.setMediaItems((1L..5).map{item(it)},2,0)
  val old=QueueTools.snapshot(p);assertEquals(listOf("4","5"),old.ids)
  p.addMediaItem(item(6));assertFalse(QueueTools.clearUpcoming(p,old));assertEquals(6,p.mediaItemCount)
  assertTrue(QueueTools.clearUpcoming(p,QueueTools.snapshot(p)));assertEquals(listOf("1","2","3"),(0 until p.mediaItemCount).map{p.getMediaItemAt(it).mediaId});assertEquals("3",p.currentMediaItem?.mediaId)
  p.repeatMode=Player.REPEAT_MODE_ALL;assertEquals(listOf("1","2"),QueueTools.snapshot(p).ids)
  assertTrue(QueueTools.clearUpcoming(p,QueueTools.snapshot(p)));assertEquals(1,p.mediaItemCount);assertEquals("3",p.currentMediaItem?.mediaId);p.release()
 }
 @Test fun savePlaylistPreservesFirstOccurrencesInOrder() {
  val db=LibraryDb(RuntimeEnvironment.getApplication());val id=db.createPlaylistWithTracks("Queue test",listOf(7,3,7,9))
  assertEquals(listOf(7L,3L,9L),db.trackIds(id));db.deletePlaylist(id);db.close()
 }
}
