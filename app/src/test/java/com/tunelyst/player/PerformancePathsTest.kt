package com.tunelyst.player

import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h800dp-mdpi")
class PerformancePathsTest {
 private val songs=(1L..10000L).map { Track(it,"Song $it","Artist ${it % 100}","Album $it",it,120000) }
 private class CountingTracks(private val input:List<Track>): AbstractList<Track>() {
  var reads=0
  override val size get()=input.size
  override fun get(index:Int):Track { reads++;return input[index] }
 }
 @After fun clear() { HomeContentCache.clearWarm(); MediaLibraryWarmCache.clear() }
 @Test fun currentTrackIsNotRescannedEveryPlayerTick() {
  val counted=CountingTracks(songs);val lookup=CurrentTrackLookup();val track=lookup.find(counted,10000)
  assertEquals(songs.last(),track);val initial=counted.reads;assertEquals(10000,initial)
  repeat(1000){assertSame(track,lookup.find(counted,10000))};assertEquals(initial,counted.reads)
  assertNull(lookup.find(counted,123456));val afterMiss=counted.reads
  repeat(10){assertNull(lookup.find(counted,123456))};assertEquals(afterMiss,counted.reads)
  val changed=listOf(songs.last().copy(title="Updated"))
  assertEquals("Updated",lookup.find(changed,10000)?.title)
  lookup.clear();assertEquals(songs.last(),lookup.find(counted,10000));assertTrue(counted.reads>afterMiss)
 }
 @Test fun collageStopsScanningOnceFourDifferentCoversAreFound() {
  val counted=CountingTracks(songs);val result=ArtworkViews.previewTracks(counted)
  assertEquals(songs.take(4),result);assertEquals(4,counted.reads)
  val repeated=listOf(songs[0],songs[0].copy(id=90001),songs[1],songs[2],songs[3],songs[4])
  fun key(t:Track)=if(t.albumId>=0) "album:${t.albumId}" else "track:${t.id}"
  assertEquals(repeated.distinctBy(::key).take(4),ArtworkViews.previewTracks(repeated))
  assertEquals(emptyList<Track>(),ArtworkViews.previewTracks(emptyList()))
 }
 @Test fun homeCacheFreezesMutatingInputsAndDiscardsSupersededJobs() {
  HomeContentCache.clearWarm();val jobs=ArrayDeque<Runnable>();val cache=HomeContentCache(Executor{jobs.add(it)})
  val mutable=songs.take(20).toMutableList();var callbacks=0
  assertNull(cache.get(mutable,emptySet(),emptyMap()){callbacks++})
  mutable.removeAt(0)
  assertNull(cache.get(mutable,emptySet(),emptyMap()){callbacks++})
  jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,callbacks)
  jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,callbacks)
  val content=cache.get(mutable,emptySet(),emptyMap()){callbacks++}!!
  assertFalse(content.vibes.any{it.id==1L});assertTrue(jobs.isEmpty())
  repeat(20){assertSame(content,cache.get(mutable,emptySet(),emptyMap()){callbacks++})};assertTrue(jobs.isEmpty())
  cache.close()
 }
 @Test fun slowLibraryWorkerDoesNotBlockOtherStartupWork() {
  val controller=Robolectric.buildActivity(MainActivity::class.java).create();val activity=controller.get()
  fun executor(name:String):ExecutorService=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity) as ExecutorService
  val library=executor("libraryIo");val other=executor("io");assertNotSame(library,other)
  val started=CountDownLatch(1);val release=CountDownLatch(1);val probe=CountDownLatch(1)
  try {
   library.execute{started.countDown();release.await(10,TimeUnit.SECONDS)}
   assertTrue("library worker didn't start",started.await(5,TimeUnit.SECONDS))
   other.execute{probe.countDown()}
   assertTrue("unrelated work was blocked by the library worker",probe.await(5,TimeUnit.SECONDS))
  } finally {release.countDown();controller.destroy()}
 }
}
