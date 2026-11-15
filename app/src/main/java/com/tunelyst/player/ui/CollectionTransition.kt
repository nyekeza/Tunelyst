package com.tunelyst.player

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Best-effort decoration only; failure never blocks opening the collection. */
internal class CollectionTransition(private val activity: Activity, private val handler: Handler) {
    private val decor = activity.window.decorView as? ViewGroup
    private val origin = IntArray(2)
    private var image: ImageView? = null
    fun capture(source: View?) {
        val parent = decor ?: return
        if (source == null || source.width <= 0 || source.height <= 0 || !ValueAnimator.areAnimatorsEnabled()) return
        image = runCatching {
            val start = IntArray(2); source.getLocationOnScreen(start); parent.getLocationOnScreen(origin)
            val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            source.draw(Canvas(bitmap))
            ImageView(activity).apply {
                setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
                layout(0, 0, source.width, source.height); x = (start[0] - origin[0]).toFloat(); y = (start[1] - origin[1]).toFloat()
                pivotX = 0f; pivotY = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                parent.overlay.add(this)
            }
        }.getOrNull()
    }
    fun animate(destination: View?, stillValid: () -> Boolean) {
        val snapshot = image ?: return
        val parent = decor ?: return
        val clear = Runnable { snapshot.animate().cancel(); parent.overlay.remove(snapshot) }
        handler.postDelayed(clear, 1000)
        if (destination == null) clear.run() else destination.post {
            if (!destination.isAttachedToWindow || !stillValid()) clear.run()
            else {
                val end = IntArray(2); destination.getLocationOnScreen(end); parent.getLocationOnScreen(origin)
                snapshot.animate().x((end[0] - origin[0]).toFloat()).y((end[1] - origin[1]).toFloat())
                    .scaleX(destination.width.toFloat() / snapshot.width.coerceAtLeast(1))
                    .scaleY(destination.height.toFloat() / snapshot.height.coerceAtLeast(1))
                    .alpha(0f).setDuration(280).withEndAction { parent.overlay.remove(snapshot); handler.removeCallbacks(clear) }.start()
            }
        }
    }
}
