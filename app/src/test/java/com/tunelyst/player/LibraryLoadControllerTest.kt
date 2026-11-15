package com.tunelyst.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class LibraryLoadControllerTest {
 private class Jobs:Executor { val q=ArrayDeque<Runnable>();override fun execute(command:Runnable){q.add(command)};fun next(){assertFalse(q.isEmpty());q.remove().run()} }
 private class Host(override val context:Context):LibraryLoadController.Host {
  override val io=Jobs();override val main=Handler(Looper.getMainLooper());override val isDestroyed=false
  override var tracks=emptyList<Track>();override var index=LibraryIndex(emptyList());override var scanned=false;override var loading=false;override var scanError:String?=null
  override val query="";override val screen="Home";override val groupIds:List<Long>?=null;var renders=0; var partialRefreshes=0; var lastArtworkInvalidated=false; var supportsPartial=false
  var access=true;override fun hasAccess()=access;override fun render(){renders++}
  override fun refreshHomeSilently(artworkInvalidated:Boolean):Boolean { partialRefreshes++;lastArtworkInvalidated=artworkInvalidated;return supportsPartial }
  override fun scheduleSearch(query:String){};override fun invalidateArtwork(){}
 }
 @org.junit.Before fun resetWarmCache() { MediaLibraryWarmCache.clear() }
 @org.junit.After fun clearWarmCache() { MediaLibraryWarmCache.clear(); RuntimeEnvironment.getApplication().getSharedPreferences("library_filters",Context.MODE_PRIVATE).edit().clear().commit() }
 @Test fun mediaArtworkRefreshCanUpdateHomeWithoutFullPageRebuild() {
  val host=Host(RuntimeEnvironment.getApplication());host.scanned=true;host.supportsPartial=true
  val loader=LibraryLoadController(host);loader.scan(silent=true,refreshArtwork=true)
  host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(0,host.renders);assertEquals(1,host.partialRefreshes);assertTrue(host.lastArtworkInvalidated)
  loader.close()
 }
 @Test fun cacheRestorationHonorsFiltersWithoutDiscardingRawLibrary() {
  val c=RuntimeEnvironment.getApplication(); val prefs=c.getSharedPreferences("library_filters",Context.MODE_PRIVATE)
  prefs.edit().clear().putStringSet("folders",setOf("external_primary:Private")).commit()
  val songs=listOf(Track(1,"Visible","A","B",42,120000,"Music","external_primary"), Track(2,"Hidden","A","B",42,120000,"Private","external_primary"))
  MediaLibraryCache(c).write(songs);val host=Host(c);val loader=LibraryLoadController(host)
  loader.loadOnStart();host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(listOf(1L),host.tracks.map{it.id});assertEquals(songs,loader.allTracks)
  assertEquals(songs,MediaLibraryCache(c).read());loader.close();prefs.edit().clear().commit()
 }
 @Test fun cachedStartupShowsSongsBeforeIndexAndDefersRefresh() {
  val c=RuntimeEnvironment.getApplication();val songs=listOf(Track(14,"Cached song","Artist","Album",42,120000,"Music","external_primary",1))
  MediaLibraryCache(c).write(songs);val host=Host(c);val loader=LibraryLoadController(host)
  loader.loadOnStart();assertTrue(loader.isCacheRestorePending);assertTrue(host.loading)
  host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(songs,host.tracks);assertTrue(loader.isCacheRestorePending);assertFalse(host.loading)
  loader.scan(silent=false,refreshArtwork=true)
  host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertFalse(loader.isCacheRestorePending);assertEquals(songs,host.tracks);assertTrue(host.loading);assertTrue(host.io.q.isNotEmpty())
  loader.close()
 }

 @Test fun recreatedActivityUsesMemoryImmediatelyAndRefreshesSilently() {
  val c=RuntimeEnvironment.getApplication();c.getSharedPreferences("library_filters",Context.MODE_PRIVATE).edit().clear().commit()
  val songs=listOf(Track(1,"Warm","Artist","Album",42,120000,"Music","external_primary"))
  MediaLibraryCache(c).write(songs);val first=Host(c);val initial=LibraryLoadController(first)
  initial.loadOnStart();first.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  first.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle();val index=first.index
  assertNotNull(MediaLibraryWarmCache.get(LibraryFilters(c).snapshot()));initial.close()
  val second=Host(c);val reopened=LibraryLoadController(second)
  reopened.loadOnStart()
  assertEquals(songs,second.tracks);assertSame(index,second.index);assertTrue(second.scanned);assertFalse(second.loading)
  assertFalse(reopened.isCacheRestorePending);assertEquals(1,second.io.q.size)
  reopened.onStart();assertEquals("Starting while refresh is running must not queue a duplicate",1,second.io.q.size)
  assertFalse(second.loading);reopened.close()
 }
 @Test fun unchangedRefreshRetainsPublishedSnapshotsWithoutRebuildingOrWriting() {
  val c=RuntimeEnvironment.getApplication();c.getSharedPreferences("library_filters",Context.MODE_PRIVATE).edit().clear().commit()
  val songs=ArrayList<Track>();val index=LibraryIndex(songs);val config=LibraryFilters(c).snapshot()
  MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(songs,songs,index,config))
  val host=Host(c);val loader=LibraryLoadController(host);loader.loadOnStart()
  assertSame(songs,host.tracks);assertSame(songs,loader.allTracks)
  host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertSame(songs,host.tracks);assertSame(songs,loader.allTracks);assertSame(index,host.index)
  assertEquals(1,host.renders);assertTrue(host.io.q.isEmpty());loader.close()
 }
 @Test fun changedFiltersNeverReuseAnOutdatedVisibleSnapshot() {
  val c=RuntimeEnvironment.getApplication();val prefs=c.getSharedPreferences("library_filters",Context.MODE_PRIVATE);prefs.edit().clear().commit()
  val songs=listOf(Track(1,"Visible","A","B",1,120000,"Music","external_primary"),Track(2,"Hidden","A","B",1,120000,"Private","external_primary"))
  MediaLibraryCache(c).write(songs);val filters=LibraryFilters(c)
  MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(songs,songs,LibraryIndex(songs),filters.snapshot()))
  prefs.edit().putStringSet("folders",setOf("external_primary:Private")).commit()
  val host=Host(c);val loader=LibraryLoadController(host);loader.loadOnStart()
  assertTrue(host.loading);assertTrue(host.tracks.isEmpty())
  host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertEquals(listOf(1L),host.tracks.map{it.id});loader.close()
 }
 @Test fun revokedPermissionDoesNotRevealOrRetainWarmData() {
  val c=RuntimeEnvironment.getApplication();val prefs=c.getSharedPreferences("library_filters",Context.MODE_PRIVATE);prefs.edit().clear().commit()
  val songs=listOf(Track(1,"Private","A","B",1,1000));val config=LibraryFilters(c).snapshot()
  MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(songs,songs,LibraryIndex(songs),config))
  val noAccess=Host(c).apply {access=false};val denied=LibraryLoadController(noAccess);denied.loadOnStart()
  assertTrue(noAccess.tracks.isEmpty());assertNull(MediaLibraryWarmCache.get(config));assertTrue(noAccess.io.q.isEmpty())
  MediaLibraryWarmCache.put(MediaLibraryWarmCache.Snapshot(songs,songs,LibraryIndex(songs),config))
  val host=Host(c);val loader=LibraryLoadController(host);loader.loadOnStart();assertEquals(songs,host.tracks)
  host.access=false;host.io.next();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertTrue(host.tracks.isEmpty());assertFalse(host.scanned);assertNull(MediaLibraryWarmCache.get(config));loader.close()
 }

 @Test fun filterChangesDuringCacheReadDoNotPublishStaleSongs() {
  val c=RuntimeEnvironment.getApplication();val prefs=c.getSharedPreferences("library_filters",Context.MODE_PRIVATE);prefs.edit().clear().commit()
  val songs=listOf(Track(1,"Private","A","B",1,1000,"Private","external_primary"));MediaLibraryCache(c).write(songs)
  val host=Host(c);val loader=LibraryLoadController(host);loader.loadOnStart();host.io.next()
  prefs.edit().putStringSet("folders",setOf("external_primary:Private")).commit()
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertTrue(host.tracks.isEmpty());assertFalse(host.scanned);assertNull(MediaLibraryWarmCache.get(LibraryFilters(c).snapshot()))
  assertTrue(host.io.q.isNotEmpty());loader.close()
 }
}
