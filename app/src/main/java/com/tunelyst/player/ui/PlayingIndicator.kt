package com.tunelyst.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

/** Animated equalizer for the current item; pauses with playback and detaches cleanly. */
internal class PlayingIndicator(context: Context, color: Int) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }
    private var current = false
    private var playing = false
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = INVISIBLE }
    fun setState(isCurrent: Boolean, isPlaying: Boolean) {
        current = isCurrent; playing = isPlaying
        visibility = if (current) VISIBLE else INVISIBLE
        syncAnimation(); invalidate()
    }
    private fun syncAnimation() {
        if (current && playing && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            if (!animator.isStarted) animator.start()
        } else if (animator.isStarted) { animator.cancel(); phase = 0f }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat(); val height = height.toFloat()
        if (width <= 0 || height <= 0) return
        brush.strokeWidth = width * 0.12f
        for (i in 0..2) {
            val fraction = if (playing) 0.33f + 0.60f * ((sin((phase * 6.283185 + i * 1.95)) + 1.0) / 2.0).toFloat() else 0.30f + i * 0.15f
            val x = width * (0.25f + i * 0.25f)
            canvas.drawLine(x, height * 0.82f, x, height * (0.82f - 0.62f * fraction), brush)
        }
    }
}
