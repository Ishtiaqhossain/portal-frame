package com.portalhacks.frame

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log

/**
 * On-device face detection used to make the cinematic Ken Burns motion drift
 * toward people instead of a random point — "the frame keeps faces in view".
 *
 * Uses Android's built-in [android.media.FaceDetector] (classic, since API 1) so
 * it works on Portal with NO Google Mobile Services and NO extra dependencies.
 * Detection runs on a small RGB_565 copy (it requires that config and an even
 * width) so it's cheap (~tens of ms) at slide-change time.
 */
internal object FaceFocus {

    private const val TAG = "PortalFrame"
    private const val MAX_DIM = 320 // downscale target for detection
    private const val MAX_FACES = 5

    /**
     * @return {fx, fy} the normalised [0,1] centre of the most prominent face, or
     *         null when no face is found (or detection fails).
     */
    @JvmStatic
    fun find(src: Bitmap?): FloatArray? {
        if (src == null || src.width < 2 || src.height < 2) {
            return null
        }
        var scaled: Bitmap? = null
        var rgb565: Bitmap? = null
        try {
            val scale = minOf(1f, MAX_DIM / maxOf(src.width, src.height).toFloat())
            var w = maxOf(2, Math.round(src.width * scale))
            val h = maxOf(2, Math.round(src.height * scale))
            if ((w and 1) == 1) {
                w-- // FaceDetector requires an even width
            }
            scaled = Bitmap.createScaledBitmap(src, w, h, true)
            // FaceDetector requires RGB_565.
            rgb565 = if (scaled.config == Bitmap.Config.RGB_565) {
                scaled
            } else {
                scaled.copy(Bitmap.Config.RGB_565, false)
            }
            if (rgb565 == null) {
                return null
            }

            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val n = FaceDetector(w, h, MAX_FACES).findFaces(rgb565, faces)
            if (n <= 0) {
                return null
            }
            // Most prominent = widest eye distance.
            var best: FaceDetector.Face? = null
            var bestEyes = -1f
            for (i in 0 until n) {
                val d = faces[i]!!.eyesDistance()
                if (d > bestEyes) {
                    bestEyes = d
                    best = faces[i]
                }
            }
            val mid = PointF()
            best!!.getMidPoint(mid)
            val fx = clamp01(mid.x / w)
            val fy = clamp01(mid.y / h)
            Log.i(TAG, "FaceFocus: $n face(s), focus=$fx,$fy")
            return floatArrayOf(fx, fy)
        } catch (t: Throwable) {
            Log.w(TAG, "FaceFocus failed: $t")
            return null
        } finally {
            if (rgb565 != null && rgb565 !== scaled) {
                rgb565.recycle()
            }
            if (scaled != null && scaled !== src) {
                scaled.recycle()
            }
        }
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
}
