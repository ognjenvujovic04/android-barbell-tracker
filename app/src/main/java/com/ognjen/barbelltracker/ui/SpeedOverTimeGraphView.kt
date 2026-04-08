package com.ognjen.barbelltracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.ognjen.barbelltracker.domain.BarVelocitySample
import kotlin.math.max

/**
 * Line chart of bar speed (cm/s) vs time (ms), with a vertical playhead at current video time.
 */
class SpeedOverTimeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val series = mutableListOf<BarVelocitySample>()
    private var playbackMs: Long = 0L

    private val bgPaint = Paint().apply { color = Color.parseColor("#DDDDDD") }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 11f * density
    }
    private val labelPaintLeft = Paint(labelPaint).apply { textAlign = Paint.Align.LEFT }
    private val labelPaintRight = Paint(labelPaint).apply { textAlign = Paint.Align.RIGHT }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 128, 128, 128)
        strokeWidth = 0.5f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#896CFE")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#896CFE")
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.ognjen.barbelltracker.R.color.bounding_box_color)
        strokeWidth = 2f * density
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    private val chartPath = Path()

    fun setSeries(samples: List<BarVelocitySample>) {
        series.clear()
        series.addAll(samples.sortedBy { it.timestampMs })
        playbackMs = 0L
        rebuildPath()
        invalidate()
    }

    fun setPlaybackPositionMs(positionMs: Long) {
        playbackMs = positionMs
        invalidate()
    }

    fun clear() {
        series.clear()
        playbackMs = 0L
        chartPath.reset()
        invalidate()
    }

    private fun rebuildPath() {
        chartPath.reset()
        if (series.size < 2) return
        val padL = 42f * density
        val padR = 12f * density
        val padT = 14f * density
        val padB = 28f * density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = padL
        val right = w - padR
        val top = padT
        val bottom = h - padB
        val t0 = series.first().timestampMs
        val t1 = series.last().timestampMs
        val yMax = max(series.maxOf { it.speedCmPerS } * 1.08f, 1e-3f)

        val first = series.first()
        chartPath.moveTo(timeToX(first.timestampMs, t0, t1, left, right), speedToY(first.speedCmPerS, top, bottom, yMax))
        for (i in 1 until series.size) {
            val s = series[i]
            chartPath.lineTo(timeToX(s.timestampMs, t0, t1, left, right), speedToY(s.speedCmPerS, top, bottom, yMax))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val padL = 42f * density
        val padR = 12f * density
        val padT = 14f * density
        val padB = 28f * density
        val left = padL
        val right = width - padR
        val top = padT
        val bottom = height - padB

        if (series.isEmpty()) {
            canvas.drawText(
                "Process video to see speed",
                width / 2f,
                height / 2f,
                emptyPaint
            )
            return
        }

        val t0 = series.first().timestampMs
        val t1 = series.last().timestampMs
        val yMax = max(series.maxOf { it.speedCmPerS } * 1.08f, 1e-3f)

        // Axes
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        // Grid (horizontal)
        val midY = speedToY(yMax / 2f, top, bottom, yMax)
        canvas.drawLine(left, midY, right, midY, gridPaint)

        canvas.drawText("0", left - 4f * density, bottom + 4f * density, labelPaintRight)
        canvas.drawText(
            "%.0f".format(yMax),
            left - 4f * density,
            top + 4f * density,
            labelPaintRight
        )
        canvas.drawText("Speed (cm/s)", left, top - 4f * density, labelPaintLeft)

        val tEndLabel = if (t1 > t0) "${((t1 - t0) / 1000f).format(1)} s" else "0 s"
        canvas.drawText(
            tEndLabel,
            right,
            bottom + 16f * density,
            labelPaintRight
        )

        if (series.size >= 2) {
            canvas.drawPath(chartPath, linePaint)
        } else {
            val cx = timeToX(series[0].timestampMs, t0, t1, left, right)
            val cy = speedToY(series[0].speedCmPerS, top, bottom, yMax)
            canvas.drawCircle(cx, cy, 4f * density, dotFillPaint)
        }

        // Playhead (video time mapped to same axis as samples)
        val span = (t1 - t0).coerceAtLeast(1L)
        val px = when {
            playbackMs <= t0 -> left
            playbackMs >= t1 -> right
            else -> left + (playbackMs - t0).toFloat() / span.toFloat() * (right - left)
        }
        canvas.drawLine(px, top, px, bottom, playheadPaint)
    }

    private fun timeToX(t: Long, t0: Long, t1: Long, left: Float, right: Float): Float {
        val span = (t1 - t0).coerceAtLeast(1L)
        return left + (t - t0).toFloat() / span.toFloat() * (right - left)
    }

    private fun speedToY(speed: Float, top: Float, bottom: Float, yMax: Float): Float {
        val t = (speed / yMax).coerceIn(0f, 1f)
        return bottom - t * (bottom - top)
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}
