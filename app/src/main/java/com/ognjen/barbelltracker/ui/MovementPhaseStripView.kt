package com.ognjen.barbelltracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.ognjen.barbelltracker.R
import com.ognjen.barbelltracker.domain.PhaseDirection
import com.ognjen.barbelltracker.domain.PhaseSegment
import kotlin.math.max

/**
 * Horizontal strip aligned with [SpeedOverTimeGraphView] time axis: green = upward bar motion, red = downward.
 * Uses the same left/right padding as the speed chart via [ChartGeometry].
 */
class MovementPhaseStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val segments = mutableListOf<PhaseSegment>()
    private var timeStartMs: Long = 0L
    private var timeEndMs: Long = 1L
    private var playbackMs: Long = 0L

    private val bgPaint = Paint().apply { color = Color.parseColor("#DDDDDD") }
    private val upwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E7D32") }
    private val downwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C62828") }
    private val stationaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BDBDBD") }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.bounding_box_color)
        strokeWidth = 2f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    fun setPhaseData(segments: List<PhaseSegment>, timeStartMs: Long, timeEndMs: Long) {
        this.segments.clear()
        this.segments.addAll(segments)
        this.timeStartMs = timeStartMs
        this.timeEndMs = if (timeEndMs > timeStartMs) timeEndMs else timeStartMs + 1L
        playbackMs = 0L
        invalidate()
    }

    fun setPlaybackPositionMs(positionMs: Long) {
        playbackMs = positionMs
        invalidate()
    }

    fun clear() {
        segments.clear()
        timeStartMs = 0L
        timeEndMs = 1L
        playbackMs = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = ChartGeometry.padLeftPx(resources)
        val padR = ChartGeometry.padRightPx(resources)
        val padV = 4f * density
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val left = padL
        val right = w - padR
        val top = padV
        val bottom = h - padV
        if (right <= left || bottom <= top) return

        val t0 = timeStartMs
        val t1 = timeEndMs
        val span = (t1 - t0).coerceAtLeast(1L)

        if (segments.isEmpty()) {
            canvas.drawText(
                if (width > 0) "No phase data" else "",
                w / 2f,
                h / 2f + emptyPaint.textSize / 3f,
                emptyPaint
            )
            canvas.drawRect(left, top, right, bottom, borderPaint)
            return
        }

        for (seg in segments) {
            val s0 = max(seg.startMs, t0)
            val s1 = minOf(seg.endMs, t1)
            if (s1 < s0) continue
            val x0 = left + (s0 - t0).toFloat() / span.toFloat() * (right - left)
            val x1 = left + (s1 - t0).toFloat() / span.toFloat() * (right - left)
            val paint = when (seg.direction) {
                PhaseDirection.UP -> upwardPaint
                PhaseDirection.DOWN -> downwardPaint
                PhaseDirection.STATIONARY -> stationaryPaint
            }
            canvas.drawRect(x0, top, x1.coerceAtLeast(x0 + 1f), bottom, paint)
        }

        canvas.drawRect(left, top, right, bottom, borderPaint)

        val px = when {
            playbackMs <= t0 -> left
            playbackMs >= t1 -> right
            else -> left + (playbackMs - t0).toFloat() / span.toFloat() * (right - left)
        }
        canvas.drawLine(px, top, px, bottom, playheadPaint)
    }
}
