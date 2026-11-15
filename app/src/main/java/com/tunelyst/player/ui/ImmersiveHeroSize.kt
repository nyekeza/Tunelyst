package com.tunelyst.player

/** Shared artwork height below the status area on Home and collection pages. */
internal object ImmersiveHeroSize {
    const val BASE_DP = 248
    fun height(basePixels: Int, topPadding: Int): Int = basePixels + topPadding.coerceAtLeast(0)
}
