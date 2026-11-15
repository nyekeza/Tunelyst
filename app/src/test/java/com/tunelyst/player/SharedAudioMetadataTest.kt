package com.tunelyst.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedAudioMetadataTest {
 private val uri=Uri.parse("content://test.shared/audio/123")
 private fun waitFor(check:()->Boolean) { repeat(400) { Shadows.shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };assertTrue("Timed out",check()) }
 private class Provider: ContentProvider() {
  override fun onCreate()=true
  override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor = MatrixCursor(projection!!).apply { addRow(arrayOf<Any>("Actual name.mp3",1234L)) }
  override fun getType(uri:Uri)="audio/mpeg"
  override fun insert(uri:Uri,values:ContentValues?):Uri?=null
  override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
  override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
 }
 @Test fun inaccessibleEmbeddedTagsFallBackToProviderMetadataAndPreserveSharedFlag() {
  val c=RuntimeEnvironment.getApplication();ShadowContentResolver.registerProviderInternal("test.shared",Provider())
  val item=SharedAudio.item(uri,"123");val data=SharedAudioMetadata.read(c,uri,item.mediaMetadata)
  assertEquals("Actual name.mp3",data.title);assertEquals("Unknown artist",data.artist)
  assertEquals("audio/mpeg",data.extras?.getString(SharedAudioMetadata.MIME));assertEquals(1234L,data.extras?.getLong(SharedAudioMetadata.SIZE))
  assertTrue(data.extras!!.getBoolean(SharedAudio.KEY));assertTrue(data.extras!!.getBoolean(SharedAudioMetadata.RESOLVED))
 }
 @Test fun asyncEnrichmentPreservesItemIdentityAndPausedPlayback() {
  val c=RuntimeEnvironment.getApplication();val player=ExoPlayer.Builder(c).build();val item=SharedAudio.item(uri,"Fallback")
  val calls=AtomicInteger();val controller=SharedAudioMetadataController(c,player,read={_,_,old->calls.incrementAndGet();old.buildUpon().setTitle("Tagged title").setArtist("Tagged artist").setAlbumTitle("Tagged album").setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER).setExtras(Bundle(old.extras).apply {putBoolean(SharedAudioMetadata.RESOLVED,true)}).build()})
  player.setMediaItem(item);waitFor { player.currentMediaItem?.mediaMetadata?.title=="Tagged title" }
  assertEquals(item.mediaId,player.currentMediaItem?.mediaId);assertEquals(uri,player.currentMediaItem?.localConfiguration?.uri)
  assertFalse(player.playWhenReady);assertEquals("Tagged artist",player.mediaMetadata.artist);assertEquals("Tagged album",player.mediaMetadata.albumTitle)
  assertArrayEquals(byteArrayOf(1,2,3),player.mediaMetadata.artworkData)
  assertTrue(SharedAudio.isShared(player.currentMediaItem));assertEquals(1,calls.get());controller.release();player.release()
 }
 @Test fun sharedSongDetailsShowAvailableFieldsAndUnavailableFallbacks() {
  val activityController=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create();val activity=activityController.get()
  val extras=Bundle().apply { putString(SharedAudioMetadata.FILE,"song.mp3");putLong(SharedAudioMetadata.SIZE,2048);putString(SharedAudioMetadata.GENRE,"Jazz") }
  val metadata=MediaMetadata.Builder().setTitle("Tagged song").setArtist("An artist").setAlbumTitle("An album").setExtras(extras).build()
  SongDetails.showShared(activity,metadata,125000)
  val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
  val message=dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
  assertTrue(message.contains("Artist: An artist"));assertTrue(message.contains("Album: An album"));assertTrue(message.contains("Duration: 2:05"))
  assertTrue(message.contains("File: song.mp3"));assertTrue(message.contains("Size: 2048 bytes"));assertTrue(message.contains("Genre: Jazz"));assertTrue(message.contains("Year: Unavailable"))
  dialog.dismiss();activityController.destroy()
 }
 @Test fun lateMetadataDoesNotReplaceAUserSelectedSong() {
  val c=RuntimeEnvironment.getApplication();val player=ExoPlayer.Builder(c).build();val started=CountDownLatch(1);val unblock=CountDownLatch(1)
  val controller=SharedAudioMetadataController(c,player,read={_,_,old->started.countDown();unblock.await(3,TimeUnit.SECONDS);old.buildUpon().setTitle("Stale").build()})
  player.setMediaItem(SharedAudio.item(uri,"Shared"));assertTrue(started.await(3,TimeUnit.SECONDS))
  player.setMediaItem(MediaItem.Builder().setMediaId("new").setUri("file:///new.mp3").build());unblock.countDown()
  repeat(60) { Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
  assertEquals("new",player.currentMediaItem?.mediaId);assertNotEquals("Stale",player.mediaMetadata.title)
  controller.release();player.release()
 }
}
