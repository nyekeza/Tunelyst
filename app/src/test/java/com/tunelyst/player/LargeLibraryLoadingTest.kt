package com.tunelyst.player

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class LargeLibraryLoadingTest {
 private class CountingTracks(private val input:List<Track>):AbstractList<Track>() {
  var reads=0
  override val size get()=input.size
  override fun get(index:Int):Track { reads++;return input[index] }
 }
 @Test fun songRowsAllocateOnDemandAndPreserveQueueIndices() {
  val songs=CountingTracks((1L..30000L).map { Track(it,"Song $it","Artist","Album",1,120000) })
  val rows=LibraryListAdapter.SongRows(songs)
  assertEquals(30000,rows.size);assertEquals(0,songs.reads)
  assertEquals(LibraryListAdapter.Row.Song(songs[29999],29999),rows[29999]);assertEquals(2,songs.reads)
  assertEquals(LibraryListAdapter.Row.Song(songs[0],0),rows[0]);assertEquals(4,songs.reads)
 }
 @Test fun indexBuildDefersSearchNormalizationAndPreservesDirectAndFuzzySearch() {
  val songs=listOf(Track(1,"Café Moon","Artist One","Album",1,1000),Track(2,"Moonlight","Café Artist","Night",2,1000),Track(3,"Other","Someone","Café Album",3,1000),Track(4,"Blue River","Someone","Other",4,1000))
  val index=LibraryIndex(songs)
  val delegate=LibraryIndex::class.java.getDeclaredField("searchRows\$delegate").apply{isAccessible=true}.get(index) as Lazy<*>
  val fuzzy=LibraryIndex::class.java.getDeclaredField("fuzzyWords\$delegate").apply{isAccessible=true}.get(index) as Lazy<*>
  assertFalse(delegate.isInitialized());assertFalse(fuzzy.isInitialized());assertEquals(4,index.sorted("Title").size);assertFalse(delegate.isInitialized())
  assertEquals(listOf(1L,2L,3L),index.search("cafe").map{it.id});assertTrue(delegate.isInitialized());assertFalse(fuzzy.isInitialized())
  assertEquals(listOf(4L),index.search("riverr").map{it.id});assertTrue(fuzzy.isInitialized())
  assertEquals(emptyList<Track>(),index.search("zz"))
  assertEquals(listOf(2L,1L),index.search("moon").map{it.id})
 }
 @Test fun filtersFastPathAndNestedExclusionsRemainCorrect() {
  val filters=LibraryFilters(RuntimeEnvironment.getApplication())
  val songs=listOf(Track(1,"A","A","A",1,100,"Music","external_primary"),Track(2,"B","A","A",1,100,"Music/Hidden","external_primary"),Track(3,"C","A","A",1,0,"Music/HiddenChild","external_primary"),Track(4,"D","A","A",1,500,"Music/Hidden/Nested","external_primary"),Track(5,"E","A","A",1,500,"Music/Hidden","card"))
  assertSame(songs,filters.apply(songs,LibraryFilters.Config(emptySet(),0)))
  // A trailing slash is treated like the previous implementation: it includes children,
  // but does not equal the non-slashed folder itself.
  assertEquals(listOf(1L,2L,3L,5L),filters.apply(songs,LibraryFilters.Config(setOf("external_primary:Music/Hidden/"),0)).map{it.id})
  assertEquals(listOf(3L,4L,5L),filters.apply(songs,LibraryFilters.Config(emptySet(),300)).map{it.id})
  assertEquals(listOf(1L,3L,5L),filters.apply(songs,LibraryFilters.Config(setOf("external_primary:Music/Hidden"),0)).map{it.id})
 }
}
