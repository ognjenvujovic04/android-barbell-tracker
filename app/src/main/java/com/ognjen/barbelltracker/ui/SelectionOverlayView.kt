package com.ognjen.barbelltracker.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.ognjen.barbelltracker.domain.BoundingBox

class SelectionOverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var boxes: List<BoundingBox> = emptyList()
    private var selectedBox: BoundingBox? = null
    private var onBoxSelected: ((BoundingBox) -> Unit)? = null

    // Store reference to the ImageView to get actual image bounds
    private var imageView: ImageView? = null
    private var imageBounds = RectF()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val selectedPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 150
    }

    private val textBounds = Rect()

    fun setBoxes(boundingBoxes: List<BoundingBox>) {
        this.boxes = boundingBoxes
        invalidate()
    }

    fun setImageView(imageView: ImageView) {
        this.imageView = imageView
    }

    fun setOnBoxSelectedListener(listener: (BoundingBox) -> Unit) {
        onBoxSelected = listener
    }

    private fun calculateImageBounds(): RectF {
        val imageView = this.imageView ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val drawable = imageView.drawable ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())

        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Calculate the scale factor (fitCenter behavior)
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)

        // Calculate the actual scaled image dimensions
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        // Calculate the offset to center the image
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f

        return RectF(left, top, left + scaledWidth, top + scaledHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate actual image bounds
        imageBounds = calculateImageBounds()

        val imageLeft = imageBounds.left
        val imageTop = imageBounds.top
        val imageWidth = imageBounds.width()
        val imageHeight = imageBounds.height()

        boxes.forEach { box ->
            // Convert normalized coordinates to actual screen coordinates
            // relative to the actual image bounds
            val left = imageLeft + (box.x1 * imageWidth)
            val top = imageTop + (box.y1 * imageHeight)
            val right = imageLeft + (box.x2 * imageWidth)
            val bottom = imageTop + (box.y2 * imageHeight)

            val paint = if (box == selectedBox) selectedPaint else boxPaint
            canvas.drawRect(left, top, right, bottom, paint)

            val label = "ID: ${box.id}"
            labelPaint.getTextBounds(label, 0, label.length, textBounds)
            canvas.drawRect(
                left,
                top - textBounds.height() - 10,
                left + textBounds.width() + 16,
                top,
                labelBgPaint
            )
            canvas.drawText(label, left + 8, top - 8, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // Recalculate image bounds for touch detection
            imageBounds = calculateImageBounds()

            val imageLeft = imageBounds.left
            val imageTop = imageBounds.top
            val imageWidth = imageBounds.width()
            val imageHeight = imageBounds.height()

            val x = event.x
            val y = event.y

            boxes.forEach { box ->
                // Convert normalized coordinates to actual screen coordinates
                val left = imageLeft + (box.x1 * imageWidth)
                val top = imageTop + (box.y1 * imageHeight)
                val right = imageLeft + (box.x2 * imageWidth)
                val bottom = imageTop + (box.y2 * imageHeight)

                if (x in left..right && y in top..bottom) {
                    selectedBox = box
                    onBoxSelected?.invoke(box)
                    invalidate()
                    return true
                }
            }
        }
        return true
    }

    fun getSelectedBox(): BoundingBox? = selectedBox
}