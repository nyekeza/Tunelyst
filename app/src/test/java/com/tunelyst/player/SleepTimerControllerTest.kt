package com.tunelyst.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class SleepTimerControllerTest {
 @Test fun timedCancelAndEndOfSongManageSuppressionAndVolume() {
  val p=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build();p.setMediaItems(listOf(MediaItem.fromUri("file:///song1.mp3"), MediaItem.fromUri("file:///song2.mp3")))
  val suppressed=mutableListOf<Boolean>();var deadline=0L;var cancels=0
  val timer=SleepTimerController(p,{ suppressed.add(it) },{deadline=it},{cancels++})
  p.volume=0.65f
  assertTrue(timer.set(30000,false,true));assertTrue(deadline>0)
  assertTrue(timer.set(0,false,false));assertEquals(0.65f,p.volume,0.001f)
  assertTrue(timer.set(0,true,true));assertTrue(suppressed.last())
  timer.onMediaItemTransition(p.currentMediaItem,Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
  assertTrue(suppressed.last())
  timer.onMediaItemTransition(p.currentMediaItem,Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
  assertFalse(p.playWhenReady);assertFalse(suppressed.last());assertEquals(0.65f,p.volume,0.001f)
  assertFalse(timer.set(-1,false,false));assertFalse(timer.set(25L*60*60*1000,false,false));assertTrue(cancels>=2)
  timer.release();p.release()
 }
 @Test fun deadlinePausesPlaybackAndRearmsWithoutActivity() {
  val p=ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build();p.setMediaItem(MediaItem.fromUri("file:///song.mp3"));p.playWhenReady=true
  var alarm=0L;val timer=SleepTimerController(p,{}, {alarm=it},{})
  assertTrue(timer.set(1000,false,false));assertTrue(alarm>0)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
  assertFalse(p.playWhenReady);timer.release();p.release()
 }
}
