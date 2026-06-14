package com.portalhacks.frame

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Extracts a single vibrant "mood" color from a photo so the frame's edges can
 * glow with it — a living, color-matched frame (think Ambilight for photos).
 *
 * Pure Kotlin, no dependencies: downscale to a tiny grid and take a
 * saturation-weighted average, then normalise so even muted photos give a clear
 * but tasteful tint. Runs in well under a frame at slide-change time.
 */
internal object AmbientColor {

    private const val GRID = 32

    /** An opaque, pleasantly saturated color representing the photo, or null. */
    @JvmStatic
    fun extract(src: Bitmap?): Int? {
        if (src == null || src.width < 2 || src.height < 2) {
            return null
        }
        var s: Bitmap? = null
        try {
            s = Bitmap.createScaledBitmap(src, GRID, GRID, true)
            val px = IntArray(GRID * GRID)
            s.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
            var rw = 0.0
            var gw = 0.0
            var bw = 0.0
            var wsum = 0.0
            val hsv = FloatArray(3)
            for (p in px) {
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                Color.RGBToHSV(r, g, b, hsv)
                // Favour colorful, mid-bright pixels; ignore near-black/near-white.
                val sat = hsv[1]
                val v = hsv[2]
                if (v < 0.15f || v > 0.97f) {
                    continue
                }
                val w = sat * sat * (0.4 + 0.6 * v)
                rw += r * w
                gw += g * w
                bw += b * w
                wsum += w
            }
            if (wsum < 1e-3) {
                return null // a flat / monochrome photo — no glow
            }
            val r = Math.round(rw / wsum).toInt()
            val g = Math.round(gw / wsum).toInt()
            val b = Math.round(bw / wsum).toInt()
            // Normalise: lift saturation a touch and cap brightness so the glow reads
            // as color without washing out.
            Color.RGBToHSV(r, g, b, hsv)
            hsv[1] = minOf(1f, hsv[1] * 1.25f + 0.10f)
            hsv[2] = minOf(0.85f, maxOf(0.45f, hsv[2]))
            return Color.HSVToColor(hsv)
        } catch (t: Throwable) {
            return null
        } finally {
            if (s != null && s !== src) {
                s.recycle()
            }
        }
    }
}
