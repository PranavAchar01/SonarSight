package com.sixthsense.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.sixthsense.R

enum class RailCondition { CONNECTED, ACTIVE, AVAILABLE, DISABLED, DEGRADED, UNAVAILABLE }

data class SystemCheckpoint(
    val label: String,
    val state: String,
    val condition: RailCondition,
)

/**
 * Stable, non-interactive status rail. The line and device pictograms are drawn as one
 * accessibility node so TalkBack reads a concise system summary instead of every decoration.
 */
class SystemRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val routeTypeface = ResourcesCompat.getFont(context, R.font.barlow_semi_condensed_semibold)
        ?: Typeface.DEFAULT_BOLD
    private val bodyTypeface = ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_next)
        ?: Typeface.DEFAULT

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DIVIDER
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SECONDARY
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.SQUARE
        style = Paint.Style.STROKE
    }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG
        textAlign = Paint.Align.CENTER
        typeface = routeTypeface
        textSize = sp(10f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PRIMARY
        textAlign = Paint.Align.CENTER
        typeface = routeTypeface
        textSize = sp(13f)
        letterSpacing = 0.08f
    }
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SECONDARY
        textAlign = Paint.Align.CENTER
        typeface = bodyTypeface
        textSize = sp(12f)
    }
    private val rect = RectF()
    private val path = Path()

    private var checkpoints = listOf(
        SystemCheckpoint("GLASSES", "SETUP NEEDED", RailCondition.UNAVAILABLE),
        SystemCheckpoint("VISION", "DISABLED", RailCondition.DISABLED),
        SystemCheckpoint("AUDIO", "DISABLED", RailCondition.DISABLED),
    )

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateAccessibilitySummary()
    }

    fun setCheckpoints(values: List<SystemCheckpoint>) {
        if (values.size != 3 || values == checkpoints) return
        checkpoints = values
        updateAccessibilitySummary()
        invalidate()
    }

    private fun updateAccessibilitySummary() {
        contentDescription = "System rail. " + checkpoints.joinToString(". ") {
            "${it.label.lowercase().replaceFirstChar(Char::uppercase)}, ${it.state.lowercase()}"
        } + "."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fontExtra = ((scaledDensity / density) - 1f).coerceAtLeast(0f) * dp(34f)
        val desired = (dp(112f) + fontExtra).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val column = width / 3f
        val iconY = dp(20f)
        val railY = dp(48f)
        val labelY = dp(78f)
        val stateY = (labelY + sp(19f)).coerceAtMost(height - dp(8f))

        canvas.drawLine(column / 2f, railY, width - column / 2f, railY, linePaint)
        checkpoints.forEachIndexed { index, item ->
            val x = column * (index + 0.5f)
            drawDeviceIcon(canvas, index, x, iconY)
            drawCheckpoint(canvas, x, railY, item.condition)
            canvas.drawText(item.label, x, labelY, labelPaint)
            statePaint.color = colorFor(item.condition)
            canvas.drawText(fit(item.state, statePaint, column - dp(10f)), x, stateY, statePaint)
        }
    }

    private fun drawDeviceIcon(canvas: Canvas, index: Int, x: Float, y: Float) {
        iconPaint.color = SECONDARY
        when (index) {
            0 -> { // glasses
                canvas.drawCircle(x - dp(8f), y, dp(5f), iconPaint)
                canvas.drawCircle(x + dp(8f), y, dp(5f), iconPaint)
                canvas.drawLine(x - dp(3f), y, x + dp(3f), y, iconPaint)
                canvas.drawLine(x - dp(13f), y - dp(1f), x - dp(17f), y - dp(4f), iconPaint)
                canvas.drawLine(x + dp(13f), y - dp(1f), x + dp(17f), y - dp(4f), iconPaint)
            }
            1 -> { // vision frame
                val s = dp(8f)
                path.reset()
                path.moveTo(x - s, y - dp(2f)); path.lineTo(x - s, y - s); path.lineTo(x - dp(2f), y - s)
                path.moveTo(x + dp(2f), y - s); path.lineTo(x + s, y - s); path.lineTo(x + s, y - dp(2f))
                path.moveTo(x + s, y + dp(2f)); path.lineTo(x + s, y + s); path.lineTo(x + dp(2f), y + s)
                path.moveTo(x - dp(2f), y + s); path.lineTo(x - s, y + s); path.lineTo(x - s, y + dp(2f))
                canvas.drawPath(path, iconPaint)
                canvas.drawCircle(x, y, dp(2.5f), iconPaint)
            }
            else -> { // speaker
                path.reset()
                path.moveTo(x - dp(10f), y - dp(4f)); path.lineTo(x - dp(5f), y - dp(4f))
                path.lineTo(x + dp(1f), y - dp(9f)); path.lineTo(x + dp(1f), y + dp(9f))
                path.lineTo(x - dp(5f), y + dp(4f)); path.lineTo(x - dp(10f), y + dp(4f)); path.close()
                canvas.drawPath(path, iconPaint)
                rect.set(x + dp(3f), y - dp(7f), x + dp(13f), y + dp(7f))
                canvas.drawArc(rect, -55f, 110f, false, iconPaint)
            }
        }
    }

    private fun drawCheckpoint(canvas: Canvas, x: Float, y: Float, condition: RailCondition) {
        val color = colorFor(condition)
        checkpointPaint.color = color
        checkpointPaint.strokeWidth = dp(2f)
        val active = condition == RailCondition.CONNECTED || condition == RailCondition.ACTIVE
        checkpointPaint.style = if (active) Paint.Style.FILL else Paint.Style.STROKE
        if (condition == RailCondition.DEGRADED) {
            rect.set(x - dp(7f), y - dp(7f), x + dp(7f), y + dp(7f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), checkpointPaint)
        } else {
            canvas.drawCircle(x, y, dp(7f), checkpointPaint)
        }
        glyphPaint.color = if (active) BG else color
        val glyph = when (condition) {
            RailCondition.CONNECTED, RailCondition.ACTIVE -> "✓"
            RailCondition.AVAILABLE -> "•"
            RailCondition.DISABLED -> "–"
            RailCondition.DEGRADED -> "!"
            RailCondition.UNAVAILABLE -> "×"
        }
        canvas.drawText(glyph, x, y - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
    }

    private fun colorFor(condition: RailCondition): Int = when (condition) {
        RailCondition.CONNECTED -> ROUTE_BLUE
        RailCondition.ACTIVE -> TRAIL_GREEN
        RailCondition.AVAILABLE, RailCondition.DISABLED -> SECONDARY
        RailCondition.DEGRADED -> AMBER
        RailCondition.UNAVAILABLE -> CORAL
    }

    private fun fit(source: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(source) <= maxWidth) return source
        var text = source
        while (text.length > 2 && paint.measureText("$text…") > maxWidth) text = text.dropLast(1)
        return "$text…"
    }

    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * scaledDensity
}

enum class DirectionCondition { OPEN, WATCH, STOP, UNKNOWN }

data class DirectionCheckpoint(
    val heading: String,
    val objectLabel: String,
    val distance: String,
    val condition: DirectionCondition,
)

/** Fixed LEFT / AHEAD / RIGHT information zones with no sweeping or continuous motion. */
class DirectionRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val routeTypeface = ResourcesCompat.getFont(context, R.font.barlow_semi_condensed_semibold)
        ?: Typeface.DEFAULT_BOLD
    private val bodyTypeface = ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_next)
        ?: Typeface.DEFAULT
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PRIMARY; textAlign = Paint.Align.CENTER; typeface = routeTypeface; textSize = sp(14f); letterSpacing = 0.08f
    }
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = routeTypeface; textSize = sp(12f)
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PRIMARY; textAlign = Paint.Align.CENTER; typeface = bodyTypeface; textSize = sp(16f)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PRIMARY; textAlign = Paint.Align.CENTER; typeface = routeTypeface; textSize = sp(23f)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = routeTypeface; textSize = sp(10f)
    }
    private val rect = RectF()
    private var checkpoints = listOf(
        DirectionCheckpoint("LEFT", "Clear", "OPEN", DirectionCondition.OPEN),
        DirectionCheckpoint("AHEAD", "Waiting", "—", DirectionCondition.UNKNOWN),
        DirectionCheckpoint("RIGHT", "Clear", "OPEN", DirectionCondition.OPEN),
    )

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateAccessibilitySummary()
    }

    fun setCheckpoints(values: List<DirectionCheckpoint>) {
        if (values.size != 3 || values == checkpoints) return
        checkpoints = values
        updateAccessibilitySummary()
        invalidate()
    }

    private fun updateAccessibilitySummary() {
        contentDescription = "Direction summary. " + checkpoints.joinToString(". ") {
            val distance = if (it.distance == "OPEN" || it.distance == "—") "" else ", ${it.distance.lowercase()}"
            "${it.heading.lowercase().replaceFirstChar(Char::uppercase)}: ${it.objectLabel.lowercase()}$distance, ${stateLabel(it.condition).lowercase()}"
        } + "."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fontExtra = ((scaledDensity / density) - 1f).coerceAtLeast(0f) * dp(54f)
        val desired = (dp(154f) + fontExtra).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = dp(4f)
        val column = (width - gap * 2f) / 3f
        checkpoints.forEachIndexed { index, item ->
            val left = index * (column + gap)
            val right = left + column
            val center = (left + right) / 2f
            val ahead = index == 1
            rect.set(left, 0f, right, height.toFloat())
            surfacePaint.color = if (ahead) RAISED else SURFACE
            canvas.drawRoundRect(rect, dp(6f), dp(6f), surfacePaint)
            borderPaint.color = if (ahead) ROUTE_BLUE else DIVIDER
            borderPaint.strokeWidth = dp(if (ahead) 2f else 1f)
            canvas.drawRoundRect(rect, dp(6f), dp(6f), borderPaint)

            val headingY = dp(24f) + (headingPaint.textSize - sp(14f)) * 0.25f
            canvas.drawText(item.heading, center, headingY, headingPaint)
            drawArrow(canvas, center, dp(39f), index)
            drawDirectionCheckpoint(canvas, center, dp(57f), item.condition)
            statePaint.color = colorFor(item.condition)
            canvas.drawText(stateLabel(item.condition), center, dp(79f) + (statePaint.textSize - sp(12f)) * 0.4f, statePaint)
            canvas.drawText(fit(item.objectLabel, objectPaint, column - dp(12f)), center, dp(105f) + (objectPaint.textSize - sp(16f)) * 0.6f, objectPaint)
            distancePaint.color = if (item.condition == DirectionCondition.OPEN) SECONDARY else PRIMARY
            canvas.drawText(fit(item.distance, distancePaint, column - dp(10f)), center,
                (height - dp(14f)).coerceAtLeast(dp(132f)), distancePaint)
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, index: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (index == 1) ROUTE_BLUE else SECONDARY
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            strokeCap = Paint.Cap.SQUARE
        }
        when (index) {
            0 -> {
                canvas.drawLine(x + dp(5f), y, x - dp(5f), y, p)
                canvas.drawLine(x - dp(5f), y, x - dp(1f), y - dp(4f), p)
                canvas.drawLine(x - dp(5f), y, x - dp(1f), y + dp(4f), p)
            }
            1 -> {
                canvas.drawLine(x, y + dp(5f), x, y - dp(5f), p)
                canvas.drawLine(x, y - dp(5f), x - dp(4f), y - dp(1f), p)
                canvas.drawLine(x, y - dp(5f), x + dp(4f), y - dp(1f), p)
            }
            else -> {
                canvas.drawLine(x - dp(5f), y, x + dp(5f), y, p)
                canvas.drawLine(x + dp(5f), y, x + dp(1f), y - dp(4f), p)
                canvas.drawLine(x + dp(5f), y, x + dp(1f), y + dp(4f), p)
            }
        }
    }

    private fun drawDirectionCheckpoint(canvas: Canvas, x: Float, y: Float, condition: DirectionCondition) {
        val color = colorFor(condition)
        checkpointPaint.color = color
        checkpointPaint.strokeWidth = dp(2f)
        checkpointPaint.style = if (condition == DirectionCondition.OPEN) Paint.Style.FILL else Paint.Style.STROKE
        if (condition == DirectionCondition.WATCH) {
            rect.set(x - dp(6f), y - dp(6f), x + dp(6f), y + dp(6f))
            canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), checkpointPaint)
        } else {
            canvas.drawCircle(x, y, dp(6f), checkpointPaint)
        }
        glyphPaint.color = if (condition == DirectionCondition.OPEN) BG else color
        val glyph = when (condition) {
            DirectionCondition.OPEN -> "✓"
            DirectionCondition.WATCH -> "!"
            DirectionCondition.STOP -> "×"
            DirectionCondition.UNKNOWN -> "?"
        }
        canvas.drawText(glyph, x, y - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
    }

    private fun stateLabel(condition: DirectionCondition): String = when (condition) {
        DirectionCondition.OPEN -> "OPEN"
        DirectionCondition.WATCH -> "WATCH"
        DirectionCondition.STOP -> "STOP"
        DirectionCondition.UNKNOWN -> "CHECKING"
    }

    private fun colorFor(condition: DirectionCondition): Int = when (condition) {
        DirectionCondition.OPEN -> TRAIL_GREEN
        DirectionCondition.WATCH -> AMBER
        DirectionCondition.STOP -> CORAL
        DirectionCondition.UNKNOWN -> SECONDARY
    }

    private fun fit(source: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(source) <= maxWidth) return source
        var text = source
        while (text.length > 2 && paint.measureText("$text…") > maxWidth) text = text.dropLast(1)
        return "$text…"
    }

    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * scaledDensity
}

private val BG = Color.parseColor("#0C1210")
private val SURFACE = Color.parseColor("#121A17")
private val RAISED = Color.parseColor("#18211E")
private val PRIMARY = Color.parseColor("#F4F1E8")
private val SECONDARY = Color.parseColor("#B7C2BC")
private val DIVIDER = Color.parseColor("#2A3531")
private val ROUTE_BLUE = Color.parseColor("#62B5E5")
private val TRAIL_GREEN = Color.parseColor("#A8C68F")
private val AMBER = Color.parseColor("#F3C45B")
private val CORAL = Color.parseColor("#FF766B")
