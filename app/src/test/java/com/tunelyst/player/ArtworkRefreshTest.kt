package com.tunelyst.player

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h800dp-mdpi")
class ArtworkRefreshTest {
 @Test fun refreshKeepsOldCoverUntilTheNewLookupFinishes() {
  val lifecycle=Robolectric.buildActivity(Activity::class.java).setup();val activity=lifecycle.get()
  val jobs=ArrayDeque<Runnable>();val loader=ArtworkLoader(activity,Executor{jobs.add(it)})
  val track=Track(55,"Song","Artist","Album",55,100000)
  val parent=FrameLayout(activity);val image=ImageView(activity);parent.addView(image,FrameLayout.LayoutParams(200,200));activity.setContentView(parent)
  val old=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);image.setImageBitmap(old);image.visibility=View.VISIBLE
  val decor=activity.window.decorView;decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));decor.layout(0,0,360,800)
  loader.invalidate();val oldDrawable=image.drawable
  loader.loadArtwork(track,image,200,clearOnMissing=true)
  assertSame(oldDrawable,image.drawable);assertEquals(View.VISIBLE,image.visibility)
  Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertFalse("A visible cover should be refreshed asynchronously",jobs.isEmpty())
  assertSame(oldDrawable,image.drawable);assertEquals(View.VISIBLE,image.visibility)
  jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertTrue("After lookup the old cover is replaced or a placeholder is shown",image.visibility == View.INVISIBLE || image.drawable !== oldDrawable)
  loader.close();lifecycle.pause().stop().destroy()
 }
}
