package com.ognjen.barbelltracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Utility class for drawing bounding boxes and markers on images after object detection
 */
class DetectionDrawer {

    companion object {
        fun drawDetection(originalBitmap: Bitmap, boundingBox: BoundingBox): Bitmap {
            // Create a mutable copy of the original bitmap to draw on
            val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)

            // Convert normalized coordinates to actual pixel positions
            val imageWidth = originalBitmap.width
            val imageHeight = originalBitmap.height

            val x1 = boundingBox.x1 * imageWidth
            val y1 = boundingBox.y1 * imageHeight
            val x2 = boundingBox.x2 * imageWidth
            val y2 = boundingBox.y2 * imageHeight

            // Calculate center point of bounding box
            val centerX = (x1 + x2) / 2
            val centerY = (y1 + y2) / 2

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

            // Draw rectangle
            canvas.drawRect(x1, y1, x2, y2, boxPaint)

            // Draw center dot
            val dotRadius = 10f
            canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)

            return resultBitmap
        }
    }
}