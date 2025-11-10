package com.ognjen.barbelltracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.ognjen.barbelltracker.domain.BoundingBox
import com.ognjen.barbelltracker.domain.VideoProcessor

class BarbellSelectionPopup(
    private val context: Context,
    private val videoProcessor: VideoProcessor,
    private val popupContainer: ConstraintLayout,
    private val button1: Button,
    private val button2: Button,
    private val firstFrameView: ImageView,
    private val textViewPopup: TextView,
    private val overlaySelectionView: SelectionOverlayView
) {

    private var onBarbellSelectedListener: (() -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null
    private var selectedBox: BoundingBox? = null

    init {
        setupListeners()
        // Pass the ImageView reference to the overlay
        overlaySelectionView.setImageView(firstFrameView)
    }

    private fun setupListeners() {
        button1.setOnClickListener {
            if (selectedBox == null) {
                Toast.makeText(context, "Please select a barbell first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            //todo
//            videoProcessor.setSelectedBarbell(selectedBox!!)
            hide()
            onBarbellSelectedListener?.invoke()
        }

        button2.setOnClickListener {
            hide()
            onCancelListener?.invoke()
        }
    }

    fun show(videoUri: Uri) {
        if (!isVideoUriValid(videoUri)) {
            Toast.makeText(context, "No video selected", Toast.LENGTH_SHORT).show()
            return
        }

        popupContainer.visibility = View.VISIBLE
        performBarbellSelection(videoUri)
    }

    fun hide() {
        popupContainer.visibility = View.GONE
    }

    private fun isVideoUriValid(uri: Uri?): Boolean {
        return uri != null
    }

    private fun performBarbellSelection(videoUri: Uri) {
        try {
            val firstFrame = videoProcessor.firstFrame(videoUri)
            val detections = videoProcessor.getFirstTrackingBoxes()

            firstFrameView.setImageBitmap(firstFrame)

            // Set boxes and invalidate after the image is set
            overlaySelectionView.post {
                overlaySelectionView.setBoxes(detections)
            }

            overlaySelectionView.setOnBoxSelectedListener { box ->
                selectedBox = box
                Toast.makeText(context, "Selected ID: ${box.id}", Toast.LENGTH_SHORT).show()
            }

            displayBoundingBoxes(detections)
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error extracting first frame: ${e.message}")
            Toast.makeText(context, "Failed to extract first frame", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayBoundingBoxes(boundingBoxes: List<BoundingBox>) {
        val detectionTextBuilder = StringBuilder()

        boundingBoxes.forEachIndexed { index, boundingBox ->
            val bboxText = """
            Detection ${index + 1}:
            Normalized: x1: ${boundingBox.x1.format(4)}, y1: ${boundingBox.y1.format(4)},
                       x2: ${boundingBox.x2.format(4)}, y2: ${boundingBox.y2.format(4)}
            Confidence: ${boundingBox.cnf.format(4)}
            ID: ${boundingBox.id}
            --------------------------
            """.trimIndent()

            detectionTextBuilder.append(bboxText).append("\n")
        }

        textViewPopup.text = detectionTextBuilder.toString()
    }

    private fun displayFirstFrame(bitmap: Bitmap) {
        firstFrameView.setImageBitmap(bitmap)
    }

    fun setOnBarbellSelectedListener(listener: () -> Unit) {
        onBarbellSelectedListener = listener
    }

    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    companion object {
        private const val ERRORTAG = "BarbelltrackerError"
    }
}