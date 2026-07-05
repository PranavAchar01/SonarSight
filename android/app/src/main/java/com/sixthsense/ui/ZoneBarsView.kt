package com.sixthsense.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sixthsense.core.DepthZones

/**
 * Judge-readable obstacle radar: three columns (LEFT / CENTER / RIGHT) that fill
 * bottom-up with the zone's nearness and shift green -> amber -> red as a surface
 * closes in. This is the metric-depth view — it moves even when nothing is
 * "detected", which is exactly the point (walls).
 */
class ZoneBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    @Volatile
    private var zones = DepthZones(0f, 0f, 0f)

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1B242E")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B98A5")
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setZones(z: DepthZones) {
        zones = z
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 8f * density
        val labelH = 16f * density
        val barW = (w - 2 * gap) / 3f
        val barH = h - labelH
        val radius = 8f * density
        val entries = listOf(
            "LEFT" to zones.left,
            "CENTER" to zones.center,
            "RIGHT" to zones.right,
        )
        entries.forEachIndexed { i, (name, v) ->
            val x0 = i * (barW + gap)
            rect.set(x0, 0f, x0 + barW, barH)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            val fillH = (barH - 4f * density) * v.coerceIn(0f, 1f)
            if (fillH > 1f) {
                fillPaint.color = heatColor(v)
                rect.set(
                    x0 + 2f * density, barH - 2f * density - fillH,
                    x0 + barW - 2f * density, barH - 2f * density,
                )
                canvas.drawRoundRect(rect, radius - 2f * density, radius - 2f * density, fillPaint)
            }
            valuePaint.color = if (v >= 0.45f) Color.WHITE else Color.parseColor("#5B6B7A")
            canvas.drawText("%.2f".format(v), x0 + barW / 2f, barH / 2f + 4f * density, valuePaint)
            canvas.drawText(name, x0 + barW / 2f, h - 3f * density, labelPaint)
        }
    }

    private fun heatColor(v: Float): Int = when {
        v >= 0.70f -> Color.parseColor("#FF4D5E")
        v >= 0.45f -> Color.parseColor("#FFC400")
        else -> Color.parseColor("#34D399")
    }
}
