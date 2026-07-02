package com.portalhacks.frame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Calendar

/**
 * Custom-drawn "designed" clock faces — the ones a restyled TextView can't do: a Solari split-flap
 * board, a Nixie-tube glow, and an aged analog dial. Selected via [setFace] ("flip" / "nixie" /
 * "analog"); [SlideshowController] swaps this in for the text clock and invalidates it on the tick
 * (per-second for analog so the second hand sweeps, per-minute otherwise). All time-only, drawn on
 * Canvas so no font assets are bundled.
 */
class ClockFaceView(context: Context) : View(context) {

    companion object {
        /** Face ids drawn by this view (vs. the restyled text clock). */
        val FACES = setOf("flip", "nixie", "analog")
    }

    private var face = "flip"

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setFace(id: String) {
        if (id == face) return
        face = id
        requestLayout()
        invalidate()
    }

    private fun dp(v: Float): Float = Ui.dp(context, v).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = when (face) {
            "nixie" -> dp(250f) to dp(80f)
            "analog" -> dp(176f) to dp(176f)
            else -> dp(300f) to dp(86f) // flip
        }
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val c = Calendar.getInstance()
        var h = c.get(Calendar.HOUR)
        if (h == 0) h = 12
        val hh = if (h < 10) "0$h" else "$h"
        val mm = c.get(Calendar.MINUTE).let { if (it < 10) "0$it" else "$it" }
        when (face) {
            "nixie" -> drawNixie(canvas, hh, mm)
            "analog" -> drawAnalog(canvas, c)
            else -> drawFlip(canvas, hh, mm)
        }
    }

    // ----------------------------------------------------------------- Solari flip

    private fun drawFlip(canvas: Canvas, hh: String, mm: String) {
        val cardW = dp(62f); val cardH = dp(86f); val gap = dp(6f)
        val colonW = dp(16f); val colonPad = dp(10f); val r = dp(9f)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = cardH * 0.6f
        var x = 0f
        val chars = listOf(hh[0], hh[1], ':', mm[0], mm[1])
        for (ch in chars) {
            if (ch == ':') {
                fill.shader = null
                fill.color = 0xFFD8D2C4.toInt()
                val cx = x + colonPad + colonW / 2f
                canvas.drawCircle(cx, cardH * 0.36f, dp(4.5f), fill)
                canvas.drawCircle(cx, cardH * 0.64f, dp(4.5f), fill)
                x += colonPad * 2 + colonW
                continue
            }
            flipCard(canvas, x, cardW, cardH, r, ch)
            x += cardW + gap
        }
    }

    private fun flipCard(canvas: Canvas, left: Float, w: Float, h: Float, r: Float, ch: Char) {
        val rect = RectF(left, 0f, left + w, h)
        fill.shader = null
        // dark card body, with a slightly lighter top half (clipped) for the split-flap sheen
        fill.color = 0xFF232326.toInt()
        canvas.drawRoundRect(rect, r, r, fill)
        fill.color = 0xFF303035.toInt()
        canvas.save()
        canvas.clipRect(left, 0f, left + w, h / 2f)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.restore()
        // the seam across the middle: a dark line with a thin highlight under it
        fill.color = 0xCC000000.toInt()
        canvas.drawRect(left, h / 2f - dp(0.75f), left + w, h / 2f + dp(0.75f), fill)
        fill.color = 0x18FFFFFF
        canvas.drawRect(left, h / 2f + dp(0.75f), left + w, h / 2f + dp(1.75f), fill)
        text.color = 0xFFF4F1E9.toInt()
        val fm = text.fontMetrics
        canvas.drawText(ch.toString(), left + w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
    }

    // ----------------------------------------------------------------- Nixie tubes

    private fun drawNixie(canvas: Canvas, hh: String, mm: String) {
        val tubeW = dp(50f); val tubeH = dp(80f); val gap = dp(10f)
        val colonW = dp(14f); val colonPad = dp(8f); val r = dp(24f)
        text.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        text.textSize = tubeH * 0.62f
        var x = 0f
        val chars = listOf(hh[0], hh[1], ':', mm[0], mm[1])
        for (ch in chars) {
            if (ch == ':') {
                nixieGlyph(canvas, x + colonPad, colonW, tubeH, ':')
                x += colonPad * 2 + colonW
                continue
            }
            // tube glass: a dark capsule with a faint warm inner glow
            val rect = RectF(x, 0f, x + tubeW, tubeH)
            fill.shader = RadialGradient(
                x + tubeW / 2f, tubeH * 0.42f, tubeH * 0.7f,
                intArrayOf(0x33FF7A1E, 0xE6140A04.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, r, r, fill)
            fill.shader = null
            stroke.color = 0x2EFFAA50
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(rect, r, r, stroke)
            nixieGlyph(canvas, x, tubeW, tubeH, ch)
            x += tubeW + gap
        }
    }

    private fun nixieGlyph(canvas: Canvas, left: Float, w: Float, h: Float, ch: Char) {
        text.shader = null
        text.color = 0xFFFF9B3A.toInt()
        text.setShadowLayer(dp(9f), 0f, 0f, 0xFFFF6A00.toInt())
        val fm = text.fontMetrics
        canvas.drawText(ch.toString(), left + w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
        text.clearShadowLayer()
    }

    // ----------------------------------------------------------------- aged analog

    private fun drawAnalog(canvas: Canvas, c: Calendar) {
        val size = dp(176f); val cx = size / 2f; val cy = size / 2f
        val radius = size / 2f - dp(3f)
        // aged cream dial + brass rim
        fill.shader = RadialGradient(
            cx, cy - dp(18f), radius, intArrayOf(0xFFF6EFDC.toInt(), 0xFFE9DCBF.toInt(), 0xFFCBB890.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        stroke.color = 0xFFB8912F.toInt(); stroke.strokeWidth = dp(5f)
        canvas.drawCircle(cx, cy, radius - dp(1f), stroke)
        stroke.color = 0xFF8A6F28.toInt(); stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, radius - dp(11f), stroke)

        // minute + hour ticks
        for (m in 0 until 60) {
            val a = m * Math.PI / 30.0
            val long = m % 5 == 0
            stroke.color = if (long) 0xFF6A531F.toInt() else 0xFFA58A4A.toInt()
            stroke.strokeWidth = if (long) dp(2f) else dp(1f)
            val outer = radius - dp(14f)
            val inner = radius - if (long) dp(22f) else dp(19f)
            canvas.drawLine(
                cx + Math.cos(a).toFloat() * outer, cy + Math.sin(a).toFloat() * outer,
                cx + Math.cos(a).toFloat() * inner, cy + Math.sin(a).toFloat() * inner, stroke,
            )
        }
        // Roman numerals
        text.shader = null; text.color = 0xFF3C2E15.toInt()
        text.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        text.textSize = dp(15f)
        val rom = arrayOf("XII", "I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI")
        val fm = text.fontMetrics
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0 - Math.PI / 2.0
            val rr = radius - dp(28f)
            canvas.drawText(
                rom[i], cx + Math.cos(a).toFloat() * rr,
                cy + Math.sin(a).toFloat() * rr - (fm.ascent + fm.descent) / 2f, text,
            )
        }
        // hands
        val hour = c.get(Calendar.HOUR) + c.get(Calendar.MINUTE) / 60.0
        val min = c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
        val sec = c.get(Calendar.SECOND).toDouble()
        drawHand(canvas, cx, cy, hour * Math.PI / 6.0, radius * 0.5f, dp(5.5f), 0xFF2C2413.toInt())
        drawHand(canvas, cx, cy, min * Math.PI / 30.0, radius * 0.74f, dp(3.5f), 0xFF2C2413.toInt())
        drawHand(canvas, cx, cy, sec * Math.PI / 30.0, radius * 0.78f, dp(1.5f), 0xFF9A2F1E.toInt())
        fill.color = 0xFFB8912F.toInt()
        canvas.drawCircle(cx, cy, dp(5.5f), fill)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angle: Double, len: Float, w: Float, color: Int) {
        val a = angle - Math.PI / 2.0 // 12 o'clock is up
        stroke.color = color; stroke.strokeWidth = w; stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx - Math.cos(a).toFloat() * dp(10f), cy - Math.sin(a).toFloat() * dp(10f),
            cx + Math.cos(a).toFloat() * len, cy + Math.sin(a).toFloat() * len, stroke,
        )
    }
}
