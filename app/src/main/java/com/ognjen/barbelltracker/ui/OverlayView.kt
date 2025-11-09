package com.ognjen.barbelltracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ognjen.barbelltracker.R
import com.ognjen.barbelltracker.domain.BoundingBox

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var pathPaint = Paint()
    private var centerPointPaint = Paint()

    private var bounds = Rect()

    // Store the last 30 center points for path drawing
    private val centerPoints = mutableListOf<PointF>()
    private val maxPathPoints = 30

    // For drawing the path
    private val pathLine = Path()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        centerPoints.clear()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        pathPaint.reset()
        centerPointPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 40f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE

        // Path line paint
        pathPaint.color = Color.RED
        pathPaint.strokeWidth = 10F
        pathPaint.style = Paint.Style.STROKE
        pathPaint.isAntiAlias = true

        // Center point paint
        centerPointPaint.color = Color.RED
        centerPointPaint.style = Paint.Style.FILL
        centerPointPaint.isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw bounding boxes
        results.forEach {
            val left = it.x1 * width
            val top = it.y1 * height
            val right = it.x2 * width
            val bottom = it.y2 * height

            canvas.drawRect(left, top, right, bottom, boxPaint)
            val drawableText = "ID: " + it.id.toString()

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )
            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
        }

        // Draw the barbell path
        drawBarbellPath(canvas)
    }

    private fun drawBarbellPath(canvas: Canvas) {
        if (centerPoints.size < 2) return

        // Reset path
        pathLine.reset()

        // Create path from center points
        val firstPoint = centerPoints[0]
        pathLine.moveTo(firstPoint.x, firstPoint.y)

        for (i in 1 until centerPoints.size) {
            val point = centerPoints[i]
            pathLine.lineTo(point.x, point.y)
        }

        // Draw the path
        canvas.drawPath(pathLine, pathPaint)

        // Draw center points with varying opacity (older points more transparent)
        for (i in centerPoints.indices) {
            val point = centerPoints[i]
            val alpha = ((i + 1).toFloat() / centerPoints.size * 255).toInt()
            centerPointPaint.alpha = alpha
            canvas.drawCircle(point.x, point.y, 6f, centerPointPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes

        // Update center points for path tracking
        updateCenterPoints(boundingBoxes)

        invalidate()
    }

    private fun updateCenterPoints(boundingBoxes: List<BoundingBox>) {
        // Assuming we're tracking the first/main barbell detection
        if (boundingBoxes.isNotEmpty()) {
            val mainBox = boundingBoxes[0] // or filter by specific ID if needed

            // Calculate center point in screen coordinates
            val centerX = (mainBox.x1 + mainBox.x2) / 2 * width
            val centerY = (mainBox.y1 + mainBox.y2) / 2 * height

            // Add to center points list
            centerPoints.add(PointF(centerX, centerY))

            // Keep only the last 30 points
            if (centerPoints.size > maxPathPoints) {
                centerPoints.removeAt(0)
            }
        }
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}