package com.tunelyst.player

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Compact four-song pages, with a peek at the next page and snapping on release. */
internal class VibesCarousel(context: Context, private val pages: LinearLayout) : HorizontalScrollView(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private var pageStep = 0
    private var didFling = false
    private var pendingPage = 0
    fun currentPage(): Int = if (pageStep > 0) (scrollX.toFloat() / pageStep).roundToInt() else pendingPage
    fun restorePage(page: Int) {
        pendingPage = page.coerceAtLeast(0)
        if (pageStep > 0) post { scrollTo(pendingPage.coerceIn(0, (pages.childCount - 1).coerceAtLeast(0)) * pageStep, 0) }
    }
    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        contentDescription = "Instant Vibes. Swipe horizontally for more songs"
        pages.setPadding(dp(16), 0, dp(32), 0)
        addView(pages)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewport = MeasureSpec.getSize(widthMeasureSpec)
        if (viewport > 0) {
            val width = (viewport - dp(48)).coerceAtLeast(dp(180))
            val nextStep = width + dp(12)
            val previousPage = if (pageStep > 0) (scrollX.toFloat() / pageStep).roundToInt() else pendingPage
            val changed = pageStep != nextStep
            pageStep = nextStep
            for (i in 0 until pages.childCount) {
                val page = pages.getChildAt(i)
                val params = page.layoutParams as LinearLayout.LayoutParams
                val margin = if (i == pages.childCount - 1) 0 else dp(12)
                if (params.width != width || params.marginEnd != margin) {
                    params.width = width; params.marginEnd = margin; page.layoutParams = params
                }
            }
            if (changed) post { scrollTo(previousPage.coerceIn(0, (pages.childCount - 1).coerceAtLeast(0)) * pageStep, 0) }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun snap(position: Int) {
        if (pageStep <= 0 || pages.childCount == 0) return
        val page = (position.toFloat() / pageStep).roundToInt().coerceIn(0, pages.childCount - 1)
        smoothScrollTo(page * pageStep, 0)
    }
    override fun fling(velocityX: Int) {
        didFling = true
        // Choose a neighboring page rather than allowing a fling to skip several.
        val position = scrollX + if (velocityX > 0) pageStep / 2 else -pageStep / 2
        snap(position)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) didFling = false
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !didFling) snap(scrollX)
        return handled
    }
}
