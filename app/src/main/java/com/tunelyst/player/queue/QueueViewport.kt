package com.tunelyst.player

/** Number of pixels at the bottom of a queue sheet that are outside its usable window. */
internal object QueueViewport {
    fun obscured(sheetTop: Int, sheetHeight: Int, parentTop: Int, parentHeight: Int, windowBottom: Int, bottomInset: Int): Int {
        if (sheetHeight <= 0 || parentHeight <= 0) return 0
        val parentBottom = parentTop.toLong() + parentHeight
        val usableBottom = minOf(windowBottom.toLong(), parentBottom - bottomInset.coerceAtLeast(0)).coerceAtLeast(parentTop.toLong())
        return (sheetTop.toLong() + sheetHeight - usableBottom).coerceIn(0L, sheetHeight.toLong()).toInt()
    }
}
