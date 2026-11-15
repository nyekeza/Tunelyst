package com.tunelyst.player

import android.Manifest
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="w360dp-h800dp-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CollectionPlaybackTest {
 private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
 private fun invoke(a:MainActivity,name:String,vararg types:Class<*>)=MainActivity::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
 private fun waitFor(check:()->Boolean) { repeat(300) { Shadows.shadowOf(Looper.getMainLooper()).idle(); if(check())return; Thread.sleep(10) }; assertTrue("Timed out",check()) }
 class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
  var items=listOf(MediaItem.Builder().setMediaId("1").setUri("file:///1").build(),MediaItem.Builder().setMediaId("2").setUri("file:///2").build(),MediaItem.Builder().setMediaId("3").setUri("file:///3").build())
  var index=0;var ready=true;var position=12345L;var setQueueCount=0
  override fun getState(): State {
   val data=items.mapIndexed { i,item -> MediaItemData.Builder("$i-${item.mediaId}").setMediaItem(item).setDurationUs(180_000_000).setIsSeekable(true).build() }
   return State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).setPlaylist(data)
    .setCurrentMediaItemIndex(index).setPlayWhenReady(ready,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    .setPlaybackState(Player.STATE_READY).setContentPositionMs(position).build()
  }
  override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*> { ready=playWhenReady;return Futures.immediateVoidFuture() }
  override fun handleSetMediaItems(mediaItems:List<MediaItem>, startIndex:Int, startPositionMs:Long):ListenableFuture<*> {
   items=mediaItems; index=startIndex.coerceIn(0,items.lastIndex);position=startPositionMs.coerceAtLeast(0);setQueueCount++;return Futures.immediateVoidFuture()
  }
  override fun handleSetRepeatMode(repeatMode:Int):ListenableFuture<*> = Futures.immediateVoidFuture()
  override fun handleSetShuffleModeEnabled(shuffleModeEnabled:Boolean):ListenableFuture<*> = Futures.immediateVoidFuture()
  override fun handlePrepare():ListenableFuture<*> = Futures.immediateVoidFuture()
  override fun handleRelease():ListenableFuture<*> = Futures.immediateVoidFuture()
  fun externalIndex(i:Int) { index=i;invalidateState() }
 }
 @Test fun decisionTracksMembershipPausedBufferingAndEnd() {
  val ids=setOf(1L,2L)
  assertEquals(CollectionPlaybackState.Action.START,CollectionPlaybackState.action(ids,3L,true,true,Player.STATE_READY))
  assertEquals(CollectionPlaybackState.Action.START,CollectionPlaybackState.action(ids,null,false,false,Player.STATE_IDLE))
  assertEquals(CollectionPlaybackState.Action.PAUSE,CollectionPlaybackState.action(ids,1L,true,true,Player.STATE_READY))
  assertEquals(CollectionPlaybackState.Action.PAUSE,CollectionPlaybackState.action(ids,2L,false,true,Player.STATE_BUFFERING))
  assertEquals(CollectionPlaybackState.Action.RESUME,CollectionPlaybackState.action(ids,2L,false,false,Player.STATE_READY))
  assertEquals(CollectionPlaybackState.Action.RESUME,CollectionPlaybackState.action(ids,1L,false,true,Player.STATE_ENDED))
 }
 @Test fun heroPausesAndResumesWithoutReplacingQueueThenStartsOtherCollection() {
  val ac=Robolectric.buildActivity(MainActivity::class.java).create();val activity=ac.get()
  Shadows.shadowOf(activity.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val songs=listOf(Track(1,"One","Artist","Album",1,180000),Track(2,"Two","Artist","Album",1,180000))
  field("tracks").set(activity,songs);field("libraryIndex").set(activity,LibraryIndex(songs));field("loading").set(activity,false);field("scanError").set(activity,null)
  val fake=FakePlayer();val session=MediaSession.Builder(activity,fake).build();val future=MediaController.Builder(activity,session.token).buildAsync();waitFor{future.isDone}
  val controller=future.get();field("player").set(activity,controller);controller.addListener(field("listener").get(activity) as Player.Listener)
  field("screen").set(activity,"Search")
  invoke(activity,"openGroup",String::class.java,List::class.java,View::class.java).invoke(activity,"Album",songs,null)
  val owner=(field("collectionHeroController\$delegate").get(activity) as Lazy<*>).value as CollectionHeroController
  val button=owner.button as ImageButton
  assertEquals("Pause Album",button.contentDescription.toString())
  button.performClick();waitFor{!fake.ready && button.contentDescription.toString()=="Resume Album"}
  assertEquals(0,fake.setQueueCount);assertEquals(12345L,fake.position);assertEquals("Search",field("screen").get(activity))
  button.performClick();waitFor{fake.ready && button.contentDescription.toString()=="Pause Album"}
  assertEquals(0,fake.setQueueCount);assertEquals(12345L,fake.position)
  fake.externalIndex(2);waitFor{button.contentDescription.toString()=="Play Album"}
  button.performClick();waitFor{fake.setQueueCount==1 && fake.items.first().mediaId=="1" && button.contentDescription.toString()=="Pause Album"}
  assertEquals("Search",field("screen").get(activity));assertEquals(2,fake.items.size)
  controller.removeListener(field("listener").get(activity) as Player.Listener);controller.release();session.release();fake.release();ac.destroy()
 }
}
