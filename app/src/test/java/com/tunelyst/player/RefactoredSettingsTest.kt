package com.tunelyst.player

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="w360dp-h800dp-mdpi")
class RefactoredSettingsTest {
 private fun f(n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun settingsRowsAndBackRemainAvailable() {
  val ac=Robolectric.buildActivity(MainActivity::class.java).create();val a=ac.get()
  f("screen").set(a,"Settings");f("settingsReturnScreen").set(a,"Home")
  MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(a)
  val root=f("content").get(a) as View
  val texts=all(root).filterIsInstance<TextView>().map { it.text.toString() }
  listOf("Settings","Appearance","Device colors","Playback","Track transitions","Playback speed","Sleep timer","Smart Shuffle controls","Equalizer","Library & backup","Refresh library","Library filters","Find likely duplicates","Audio access","Export local backup","Import local backup","Clear listening history","About Tunelyst").forEach{assertTrue("Missing $it",it in texts)}
  all(root).first{it.contentDescription=="Back from settings"}.performClick()
  assertEquals("Home",f("screen").get(a));ac.destroy()
 }
}
