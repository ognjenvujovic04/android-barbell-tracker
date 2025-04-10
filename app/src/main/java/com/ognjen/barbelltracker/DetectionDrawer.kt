package com.ognjen.barbelltracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Utility class for drawing bounding boxes, markers, and IDs on images after object detection
 */
class DetectionDrawer {

    companion object {
        fun drawDetections(originalBitmap: Bitmap, boundingBoxes: List<BoundingBox>): Bitmap {
            // Create a mutable copy of the original bitmap to draw on
            val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)

            // Convert normalized coordinates to actual pixel positions
            val imageWidth = originalBitmap.width
            val imageHeight = originalBitmap.height

            // Set up paint for rectangle
            val boxPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 5f
                isAntiAlias = true
            }

            // Set up paint for center dot
            val dotPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            // Set up paint for ID text
            val textPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            // Draw each bounding box
            for (boundingBox in boundingBoxes) {
                val x1 = boundingBox.x1 * imageWidth
                val y1 = boundingBox.y1 * imageHeight
                val x2 = boundingBox.x2 * imageWidth
                val y2 = boundingBox.y2 * imageHeight

                // Calculate center point of bounding box
                val centerX = (x1 + x2) / 2
                val centerY = (y1 + y2) / 2

                // Draw rectangle
                canvas.drawRect(x1, y1, x2, y2, boxPaint)

                // Draw center dot
                val dotRadius = 10f
                canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)

                // Draw ID text (using index + 1 to start from 1 instead of 0)
                val id = boundingBox.id
                val textX = x1 + 10 // Small offset from left edge
                val textY = y1 - 10 // Small offset above top edge
                canvas.drawText("ID: $id", textX, textY, textPaint)
            }

            return resultBitmap
        }
    }
}