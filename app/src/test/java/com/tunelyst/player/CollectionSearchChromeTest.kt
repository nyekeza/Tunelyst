package com.tunelyst.player

import android.Manifest
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
class CollectionSearchChromeTest {
 private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
 private fun call(name:String,vararg types:Class<*>)=MainActivity::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
 @Test fun openingAlbumHidesSearchControlsAndBackRestoresQueryAndFilter() {
  val ctl=Robolectric.buildActivity(MainActivity::class.java).create();val a=ctl.get()
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val songs=listOf(Track(1,"One","Artist","Café Album",1,180000), Track(2,"Two","Artist","Café Album",1,180000))
  field("tracks").set(a,songs);field("libraryIndex").set(a,LibraryIndex(songs));field("loading").set(a,false);field("scanError").set(a,null)
  field("screen").set(a,"Search");field("searchFilter").set(a,"Albums")
  val search=field("searchInput").get(a) as EditText;search.setText("cafe");call("render").invoke(a)
  assertEquals(View.VISIBLE,(field("searchHeading").get(a) as View).visibility)
  assertEquals(View.VISIBLE,(field("searchContainer").get(a) as View).visibility)
  val priorTop=(field("content").get(a) as LinearLayout).paddingTop
  call("openGroup",String::class.java,List::class.java,View::class.java).invoke(a,"Café Album",songs,null)
  assertEquals(View.GONE,(field("searchHeading").get(a) as View).visibility)
  assertEquals(View.GONE,(field("searchContainer").get(a) as View).visibility)
  assertEquals(View.GONE,(field("searchCategoryScroll").get(a) as View).visibility)
  assertEquals(View.GONE,(field("topChrome").get(a) as View).visibility)
  assertTrue((field("content").get(a) as LinearLayout).paddingTop < priorTop)
  assertFalse(search.hasFocus());assertEquals(2,(field("groupIds").get(a) as List<*>).size)
  call("closeCollection").invoke(a)
  assertEquals(View.VISIBLE,(field("searchContainer").get(a) as View).visibility)
  assertEquals(View.VISIBLE,(field("searchHeading").get(a) as View).visibility)
  assertEquals("cafe",search.text.toString());assertEquals("Albums",field("searchFilter").get(a))
  ctl.destroy()
 }
}
