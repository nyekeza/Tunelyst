package com.tunelyst.player

import android.Manifest
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
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
class SharedHomeArtworkTest {
 private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
 private fun descendants(view: View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }else emptyList()
 private fun waitFor(test:()->Boolean) { repeat(400) { Shadows.shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10) };assertTrue("Timed out",test()) }
 @Test fun sharedCoverReplacesPlaceholderThenLibraryCoverReturnsOnTrackChange() {
  val lifecycle=Robolectric.buildActivity(MainActivity::class.java).create();val activity=lifecycle.get()
  Shadows.shadowOf(activity.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val library=Track(1,"Library song","Artist","Album",1,180000)
  field("tracks").set(activity,listOf(library));field("libraryIndex").set(activity,LibraryIndex(listOf(library)));field("loading").set(activity,false);field("scanError").set(activity,null)
  val player=ExoPlayer.Builder(activity).build();val shared=SharedAudio.item(Uri.parse("content://sender/song/1"),"Shared track")
  player.setMediaItem(shared)
  val session=MediaSession.Builder(activity,player).build();val future=MediaController.Builder(activity,session.token).buildAsync();waitFor {future.isDone};val controller=future.get()
  field("player").set(activity,controller);controller.addListener(field("listener").get(activity) as Player.Listener)
  field("screen").set(activity,"Home");MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}.invoke(activity)
  val home=(field("homePage\$delegate").get(activity) as Lazy<*>).value as HomePage
  fun hero()=HomePage::class.java.getDeclaredField("heroArt").apply {isAccessible=true}.get(home) as FrameLayout
  assertTrue(descendants(hero()).filterIsInstance<TextView>().any { it.text=="♫" })
  assertFalse(descendants(hero()).any { it.contentDescription=="Shared song artwork" })
  val metadata=shared.mediaMetadata.buildUpon().setTitle("Tagged").setArtist("Shared artist")
   .setArtworkData(byteArrayOf(1,2,3,4),MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
  player.replaceMediaItem(0,shared.buildUpon().setMediaMetadata(metadata).build())
  waitFor { controller.mediaMetadata.artworkData != null }
  home.updateHomeHero()
  waitFor { descendants(hero()).filterIsInstance<ImageView>().any {it.contentDescription=="Shared song artwork"} }
  val title=HomePage::class.java.getDeclaredField("heroTitle").apply {isAccessible=true}.get(home) as TextView
  assertEquals("Tagged",title.text)
  player.setMediaItem(library.item());waitFor {controller.currentMediaItem?.mediaId=="1"};home.updateHomeHero()
  assertFalse(descendants(hero()).any {it.contentDescription=="Shared song artwork"})
  assertEquals("Library song",title.text)
  controller.removeListener(field("listener").get(activity) as Player.Listener);controller.release();session.release();player.release();lifecycle.destroy()
 }
}
