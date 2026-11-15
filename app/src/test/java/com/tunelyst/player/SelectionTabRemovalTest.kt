package com.tunelyst.player

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="w360dp-h800dp-mdpi")
class SelectionTabRemovalTest {
 private fun f(n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}
 private fun method(n:String,vararg t:Class<*>)=MainActivity::class.java.getDeclaredMethod(n,*t).apply{isAccessible=true}
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun noDuplicateSelectionHeaderButSongMenuAndSelectAllStillWork() {
  val ac=Robolectric.buildActivity(MainActivity::class.java).create();val a=ac.get()
  Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
  val songs=listOf(Track(1,"One","Artist","Album",1,180000),Track(2,"Two","Artist","Album",1,180000))
  f("tracks").set(a,songs);f("libraryIndex").set(a,LibraryIndex(songs));f("loading").set(a,false);f("scanError").set(a,null);f("screen").set(a,"Library");f("tab").set(a,"Songs")
  method("render").invoke(a)
  fun headerButtons()=all(f("content").get(a) as View).filterIsInstance<Button>().map{it.text.toString()}
  assertFalse(headerButtons().contains("Select songs"));assertFalse(headerButtons().contains("Selecting songs"))
  method("trackMenu",Track::class.java).invoke(a,songs[0])
  val menu=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
  val list=menu.listView;val index=(0 until list.adapter.count).first{list.adapter.getItem(it).toString()=="Select songs"}
  list.performItemClick(list.getChildAt(index),index,list.adapter.getItemId(index))
  val selection=(f("selection\$delegate").get(a) as Lazy<*>).value as SongSelectionController
  assertTrue(selection.active);assertFalse(headerButtons().contains("Selecting songs"))
  assertEquals(setOf(1L),selection.ids)
  assertEquals(View.VISIBLE,(f("selectionBar").get(a) as View).visibility)
  selection.showActions()
  val action=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
  val options=action.listView;options.performItemClick(options.getChildAt(0),0,options.adapter.getItemId(0))
  assertEquals(setOf(1L,2L),selection.ids)
  selection.stop();assertFalse(selection.active);assertEquals(View.GONE,(f("selectionBar").get(a) as View).visibility)
  ac.destroy()
 }
}
