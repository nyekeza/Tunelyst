package com.tunelyst.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pure timing and equal-power gain math for optional overlapping transitions. */
internal object CrossfadePolicy {
    fun overlapMs(settingMs: Int, durationMs: Long, speed: Float, repeatOne: Boolean, hasNext: Boolean): Long {
        if (settingMs <= 0 || durationMs < 1_000 || !speed.isFinite() || speed <= 0f || repeatOne || !hasNext) return 0
        return minOf(settingMs.toLong().coerceAtMost(10_000), (durationMs / speed / 3).toLong())
    }
    fun gains(elapsedMs: Long, durationMs: Long): Pair<Float, Float> {
        val fraction = if (durationMs <= 0) 1.0 else (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return cos(fraction * PI / 2).toFloat() to sin(fraction * PI / 2).toFloat()
    }
}
