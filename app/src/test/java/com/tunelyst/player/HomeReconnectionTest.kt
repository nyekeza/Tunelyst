package com.tunelyst.player

import android.Manifest
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h800dp-mdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HomeReconnectionTest {
 private fun f(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
 private fun invoke(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredMethod(name).apply {isAccessible=true}.invoke(a)
 @Test fun homeUsesCurrentServiceStateBeforeControllerConnectsAndKeepsControlsOnRefresh() {
  val c=RuntimeEnvironment.getApplication();MediaLibraryWarmCache.clear()
  val tracks=listOf(Track(1,"First","Artist","Album",10,10000),Track(2,"Current","Artist","Album",20,10000))
  val player=ExoPlayer.Builder(c).build();val publisher=PlaybackVisualState.Publisher(player)
  player.setMediaItems(tracks.map {it.item()},1,0);player.shuffleModeEnabled=true
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val a=lifecycle.get()
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  f("tracks").set(a,tracks);f("libraryIndex").set(a,LibraryIndex(tracks));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Home");f("uiStarted").set(a,true)
  assertNull(f("player").get(a));invoke(a,"render")
  val page=(f("homePage\$delegate").get(a) as Lazy<*>).value as HomePage
  fun homeField(name:String)=HomePage::class.java.getDeclaredField(name).apply {isAccessible=true}.get(page)
  assertEquals("Current",(homeField("heroTitle") as TextView).text)
  assertEquals("track:2:2",homeField("heroArtKey"))
  assertEquals(View.VISIBLE,(f("miniPlayer").get(a) as View).visibility)
  assertEquals("Current",(f("miniTitle").get(a) as TextView).text)
  val button=f("homeSmartButton").get(a) as Button;assertTrue(button.isSelected)
  val hero=homeField("homeHero");val mini=f("miniPlayer").get(a)
  page.refreshContent();assertSame(hero,homeField("homeHero"));assertSame(button,f("homeSmartButton").get(a));assertSame(mini,f("miniPlayer").get(a))
  player.seekTo(0,0);Shadows.shadowOf(Looper.getMainLooper()).idle();page.updateHomeHero();invoke(a,"updateMiniPlayer")
  assertEquals("First",(homeField("heroTitle") as TextView).text);assertEquals("track:1:1",homeField("heroArtKey"))
  assertEquals("First",(f("miniTitle").get(a) as TextView).text)
  publisher.release();assertNull(PlaybackVisualState.current(null));player.release();lifecycle.destroy();MediaLibraryWarmCache.clear()
 }
 @Test fun connectingWithoutAServiceDoesNotShowAnUnrelatedLibraryCover() {
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val a=lifecycle.get()
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val songs=listOf(Track(9,"Only library song","A","B",1,10000))
  f("tracks").set(a,songs);f("libraryIndex").set(a,LibraryIndex(songs));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Home");f("uiStarted").set(a,true);f("playbackConnecting").set(a,true)
  assertNull(PlaybackVisualState.current(null));invoke(a,"render")
  val page=(f("homePage\$delegate").get(a) as Lazy<*>).value as HomePage
  fun homeField(name:String)=HomePage::class.java.getDeclaredField(name).apply {isAccessible=true}.get(page)
  assertEquals("connecting",homeField("heroArtKey"));assertEquals(0,(homeField("heroArt") as FrameLayout).childCount)
  f("playbackConnecting").set(a,false);page.updateHomeHero();assertEquals("empty",homeField("heroArtKey"));assertEquals(0,(homeField("heroArt") as FrameLayout).childCount)
  lifecycle.destroy()
 }
 @Test fun stoppingActivityDoesNotResetVisibleControlsOrPauseServicePlayer() {
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val a=lifecycle.get()
  fun waitFor(check:()->Boolean) { repeat(400) { Shadows.shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };assertTrue("Timed out",check()) }
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val tracks=listOf(Track(1,"First","Artist","Album",10,10000),Track(2,"Playing","Artist","Album",20,10000))
  val player=ExoPlayer.Builder(a).build();player.setMediaItems(tracks.map {it.item()},1,0);player.playWhenReady=true;player.shuffleModeEnabled=true
  val publisher=PlaybackVisualState.Publisher(player)
  val session=androidx.media3.session.MediaSession.Builder(a,player).build();val future=androidx.media3.session.MediaController.Builder(a,session.token).buildAsync();waitFor{future.isDone};val controller=future.get()
  (f("player").get(a) as? androidx.media3.session.MediaController)?.removeListener(f("listener").get(a) as Player.Listener)
  f("controllerFuture").set(a,future);f("player").set(a,controller);controller.addListener(f("listener").get(a) as Player.Listener)
  f("tracks").set(a,tracks);f("libraryIndex").set(a,LibraryIndex(tracks));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Home");f("uiStarted").set(a,true);invoke(a,"render")
  val page=(f("homePage\$delegate").get(a) as Lazy<*>).value as HomePage
  fun homeField(name:String)=HomePage::class.java.getDeclaredField(name).apply {isAccessible=true}.get(page)
  val hero=homeField("homeHero");val mini=f("miniPlayer").get(a) as View;val button=f("homeSmartButton").get(a)
  lifecycle.stop()
  assertSame(controller,f("player").get(a));assertTrue(controller.isConnected);assertSame(hero,homeField("homeHero"));assertSame(button,f("homeSmartButton").get(a));assertEquals(View.VISIBLE,mini.visibility)
  assertEquals("Playing",(homeField("heroTitle") as TextView).text);assertTrue(player.playWhenReady);assertEquals("2",player.currentMediaItem?.mediaId)
  player.pause();player.seekTo(0,0);waitFor {controller.currentMediaItem?.mediaId=="1"}
  lifecycle.start()
  assertSame(controller,f("player").get(a));assertSame(hero,homeField("homeHero"));assertSame(button,f("homeSmartButton").get(a));assertEquals(View.VISIBLE,mini.visibility)
  assertEquals("First",(homeField("heroTitle") as TextView).text);assertEquals("First",(f("miniTitle").get(a) as TextView).text);assertFalse(player.playWhenReady)
  lifecycle.stop().destroy();assertFalse(controller.isConnected);assertFalse(player.playWhenReady)
  session.release();publisher.release();player.release()
 }
 @Test fun pausedTrackKeepsTheSameControllerAndViewsWhenReturningFromRecents() {
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val a=lifecycle.get()
  fun waitFor(check:()->Boolean) { repeat(400) { Shadows.shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };assertTrue("Timed out",check()) }
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val tracks=listOf(Track(1,"First","Artist","Album",10,10000),Track(2,"Paused song","Artist","Album",20,10000))
  val player=ExoPlayer.Builder(a).build();player.setMediaItems(tracks.map{it.item()},1,0);player.pause();val publisher=PlaybackVisualState.Publisher(player)
  val session=androidx.media3.session.MediaSession.Builder(a,player).build();val future=androidx.media3.session.MediaController.Builder(a,session.token).buildAsync();waitFor{future.isDone};val controller=future.get()
  f("controllerFuture").set(a,future);f("player").set(a,controller);controller.addListener(f("listener").get(a) as Player.Listener)
  f("tracks").set(a,tracks);f("libraryIndex").set(a,LibraryIndex(tracks));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Home");f("uiStarted").set(a,true);invoke(a,"render")
  val page=(f("homePage\$delegate").get(a) as Lazy<*>).value as HomePage
  fun homeField(name:String)=HomePage::class.java.getDeclaredField(name).apply{isAccessible=true}.get(page)
  val hero=homeField("homeHero");val art=homeField("heroArt") as FrameLayout;val cover=art.getChildAt(0);val mini=f("miniPlayer").get(a) as View
  assertEquals("track:2:2",homeField("heroArtKey"));assertEquals("Paused song",(f("miniTitle").get(a) as TextView).text)
  lifecycle.stop();assertSame(controller,f("player").get(a));assertTrue(controller.isConnected);assertFalse(player.playWhenReady)
  lifecycle.start()
  assertSame(controller,f("player").get(a));assertSame(hero,homeField("homeHero"));assertSame(cover,art.getChildAt(0));assertEquals(View.VISIBLE,mini.visibility)
  assertEquals("track:2:2",homeField("heroArtKey"));assertEquals("Paused song",(f("miniTitle").get(a) as TextView).text);assertFalse(player.playWhenReady)
  lifecycle.stop().destroy();assertFalse(controller.isConnected);session.release();publisher.release();player.release()
 }
 @Test fun emptyConnectedServiceShowsNoUnrelatedLibraryCoverWhileQueueRestores() {
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val a=lifecycle.get()
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val tracks=listOf(Track(1,"First","Artist","Album",10,10000),Track(2,"Saved","Artist","Album",20,10000))
  val service=ExoPlayer.Builder(a).build();val publisher=PlaybackVisualState.Publisher(service)
  f("tracks").set(a,tracks);f("libraryIndex").set(a,LibraryIndex(tracks));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Home");f("uiStarted").set(a,true);f("playbackConnecting").set(a,false)
  invoke(a,"render")
  val page=(f("homePage\$delegate").get(a) as Lazy<*>).value as HomePage
  fun homeField(name:String)=HomePage::class.java.getDeclaredField(name).apply{isAccessible=true}.get(page)
  assertEquals("empty",homeField("heroArtKey"));assertEquals(0,(homeField("heroArt") as FrameLayout).childCount)
  assertEquals(View.GONE,(f("miniPlayer").get(a) as View).visibility)
  service.setMediaItems(tracks.map{it.item()},1,0);service.pause();page.updateHomeHero();invoke(a,"updateMiniPlayer")
  assertEquals("track:2:2",homeField("heroArtKey"));assertEquals("Saved",(homeField("heroTitle") as TextView).text)
  assertEquals(View.VISIBLE,(f("miniPlayer").get(a) as View).visibility);assertEquals("Saved",(f("miniTitle").get(a) as TextView).text)
  assertFalse(service.playWhenReady);publisher.release();service.release();lifecycle.destroy()
 }
 @Test fun validOldRecommendationsStayVisibleDuringHistoryRefreshButNotForHiddenTracks() {
  val jobs=ArrayDeque<Runnable>();val cache=HomeContentCache(Executor {jobs.add(it)});val t=listOf(Track(1,"One","A","B",1,1000))
  assertNull(cache.get(t,emptySet(),emptyMap()) {});jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle()
  val before=cache.get(t,emptySet(),emptyMap()) {};assertNotNull(before)
  assertSame(before,cache.get(t,setOf(1L),emptyMap()) {})
  assertNull(cache.get(t,setOf(1L),emptyMap(),hidden=setOf(1L)) {})
  cache.close()
 }
}
