package com.portalhacks.frame

import android.graphics.Bitmap
import android.graphics.ColorMatrix

/**
 * On-device auto-enhance: analyses each photo's luminance histogram and builds a
 * gentle auto-levels (contrast stretch) + vibrance [ColorMatrix] so dull or flat
 * photos pop, without the over-processed look. Pure Kotlin, no dependencies — the
 * kind of "AI auto-enhance" a frame can do that a dumb slideshow can't.
 */
internal object PhotoEnhance {

    private const val GRID = 48

    /** A flattering ColorMatrix for this photo, or null if it's already well-exposed. */
    @JvmStatic
    fun compute(src: Bitmap?): ColorMatrix? {
        if (src == null || src.width < 2 || src.height < 2) {
            return null
        }
        var s: Bitmap? = null
        try {
            s = Bitmap.createScaledBitmap(src, GRID, GRID, true)
            val px = IntArray(GRID * GRID)
            s.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
            val hist = IntArray(256)
            for (p in px) {
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val y = (r * 77 + g * 150 + b * 29) shr 8 // luma 0..255
                hist[y]++
            }
            val total = px.size
            val lo = percentile(hist, total, 0.01f)
            val hi = percentile(hist, total, 0.99f)
            if (hi - lo < 8) {
                return null // degenerate
            }
            // Contrast stretch lo..hi -> 0..255, but hold back so it stays natural.
            val span = (hi - lo).toFloat()
            var scale = 255f / span
            scale = clamp(1f + (scale - 1f) * 0.7f, 1f, 1.8f) // ease + cap
            val translate = -lo * scale
            // Skip if the photo is already near full-range (little to gain).
            if (scale < 1.04f && lo < 6 && hi > 249) {
                return null
            }
            val levels = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            val vibrance = ColorMatrix()
            vibrance.setSaturation(1.12f) // subtle pop
            val out = ColorMatrix()
            out.postConcat(levels)
            out.postConcat(vibrance)
            return out
        } catch (t: Throwable) {
            return null
        } finally {
            if (s != null && s !== src) {
                s.recycle()
            }
        }
    }

    private fun percentile(hist: IntArray, total: Int, pct: Float): Int {
        val target = (total * pct).toInt()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) {
                return i
            }
        }
        return hist.size - 1
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (v < lo) lo else if (v > hi) hi else v
}
