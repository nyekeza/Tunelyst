package com.tunelyst.player

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class ImprovementsTest {
 private fun song(id: Long, folder: String = "Music", duration: Long = 120000, title: String = "Song", artist: String = "Artist") = Track(id,title,artist,"Album",1,duration,folder,"external_primary")
 @Test fun filtersDoNotDeleteAndIncludeUnknownDurations() {
  val context=RuntimeEnvironment.getApplication(); val prefs=context.getSharedPreferences("library_filters",Context.MODE_PRIVATE);prefs.edit().clear().commit()
  prefs.edit().putStringSet("folders",setOf("external_primary:Music/Private")).putLong("minimum_ms",30000).commit()
  val all=listOf(song(1),song(2,"Music/Private"),song(3,"Music/Private/child"),song(4,"Podcasts",5000),song(5,"Podcasts",0))
  assertEquals(listOf(1L,5L),LibraryFilters(context).apply(all).map{it.id});assertEquals(5,all.size)
  prefs.edit().clear().commit();assertEquals(all,LibraryFilters(context).apply(all))
 }
 @Test fun duplicateDetectionIsReviewOnlyAndAvoidsUnrelatedSongs() {
  val input=listOf(song(1),song(2,"Other",121500," SONG ","artist"),song(3,"Other",125000),song(4,"Other",120000,"Song","Different"),song(5,"Other",0))
  val groups=DuplicateFinder.find(input);assertEquals(1,groups.size);assertEquals(setOf(1L,2L),groups.single().map{it.id}.toSet());assertEquals(5,input.size)
 }
 @Test fun searchesAreBoundedDeduplicatedAndSorted() {
  val prefs=SearchPreferences(RuntimeEnvironment.getApplication());prefs.clear();prefs.remember(" Jazz ");prefs.remember("rock");prefs.remember("jazz")
  assertEquals(listOf("jazz","rock"),prefs.recent());repeat(15){prefs.remember("search $it")};assertEquals(12,prefs.recent().size)
  val songs=listOf(song(1,title="Z"),song(2,title="A"));prefs.setSort("Title");assertEquals(listOf(2L,1L),prefs.sorted(songs).map{it.id});prefs.setSort("Relevance");assertEquals(songs,prefs.sorted(songs));prefs.clear()
 }
 @Test fun shuffleDefaultsPreserveOrderAndWeightsChangeRanking() {
  val songs=(1L..20).map{song(it,artist="Artist $it")};val likes=setOf(1L,2L);val stats=mapOf(1L to TrackStats(2,7,10),2L to TrackStats(4,0,11))
  val old=SmartShuffle.order(songs,likes,stats,nowMs=100,random=Random(4))
  val defaults=SmartShuffle.order(songs,likes,stats,nowMs=100,random=Random(4),weights=ShuffleWeights())
  assertEquals(old,defaults)
  val off=SmartShuffle.order(songs,likes,stats,nowMs=100,random=Random(4),weights=ShuffleWeights(0.0,0.0,0.0))
  assertNotEquals(defaults,off);assertEquals(songs.map{it.id}.toSet(),off.map{it.id}.toSet())
 }
}
