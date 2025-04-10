package com.ognjen.barbelltracker

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity(), Tracker.TrackerListener {

    private lateinit var originalVideoView: VideoView
    private lateinit var processedImageView: ImageView
    private lateinit var boundingBoxTextView: TextView
    private lateinit var loadFromGalleryButton: Button
    private lateinit var detectButton: Button
    private lateinit var tracker: Tracker
    private var selectedVideoUri: Uri? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "Selected URI: $uri")
            selectedVideoUri = uri
            originalVideoView.setVideoURI(uri) // Set the video to the VideoView
            originalVideoView.start() // Start playing the video
        } else {
            Log.d(TAG, "No media selected")
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Try to load OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(ERRORTAG, "Unable to load OpenCV")
        } else {
            Log.d(TAG, "OpenCV loaded")
        }

        // Initialize views
        originalVideoView = findViewById(R.id.originalVideoView)
        processedImageView = findViewById(R.id.processedImageView)
        boundingBoxTextView = findViewById(R.id.boundingBoxTextView)
        loadFromGalleryButton = findViewById(R.id.loadFromGalleryButton)
        detectButton = findViewById(R.id.detectButton)

        // Initialize tracker
        tracker = Tracker(this, "best_float32.tflite", this)

        // Set button click listeners
        loadFromGalleryButton.setOnClickListener {
            openGallery()
        }

        detectButton.setOnClickListener {
            processVideo()
        }
    }

    private fun openGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun processVideo() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Extract the first frame from the video
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, selectedVideoUri)

            // Get the first frame (time 0) and ensure it's in ARGB_8888 format
            val firstFrameBitmap = retriever.getFrameAtTime(0)?.copy(Bitmap.Config.ARGB_8888, true)

            if (firstFrameBitmap == null) {
                Toast.makeText(this, "Failed to extract video frame", Toast.LENGTH_SHORT).show()
                return
            }

            // Release the retriever
            retriever.release()

            // Process the extracted frame
            tracker.detect(firstFrameBitmap)

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error processing video: ${e.message}", e)
            Toast.makeText(this, "Error processing video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (boundingBoxes.isEmpty()) {
            onEmptyDetect()
            return
        }

        val firstBox = boundingBoxes[0]

        // Get the first frame again for display, ensuring it's in ARGB_8888 format
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, selectedVideoUri)
        val bitmap = retriever.getFrameAtTime(0)?.copy(Bitmap.Config.ARGB_8888, true) ?: return
        retriever.release()

        // Convert normalized coordinates to absolute pixels for display
        val origWidth = bitmap.width
        val origHeight = bitmap.height

        // StringBuilder to accumulate all detections for display in the text box
        val detectionTextBuilder = StringBuilder()

        // Log and display each detection with enumeration
        boundingBoxes.forEachIndexed { index, boundingBox ->
            // Convert normalized coordinates to absolute pixels for display
            val x1Pixels = boundingBox.x1 * origWidth
            val y1Pixels = boundingBox.y1 * origHeight
            val x2Pixels = boundingBox.x2 * origWidth
            val y2Pixels = boundingBox.y2 * origHeight

            // Format the detection information
            val bboxText = """
            Detection ${index + 1}:
            Normalized: x1: ${boundingBox.x1.format(4)}, y1: ${boundingBox.y1.format(4)},
                       x2: ${boundingBox.x2.format(4)}, y2: ${boundingBox.y2.format(4)}
            Pixels: x1: ${x1Pixels.toInt()}, y1: ${y1Pixels.toInt()},
                   x2: ${x2Pixels.toInt()}, y2: ${y2Pixels.toInt()}
            Confidence: ${boundingBox.cnf.format(4)}
            Inference time: $inferenceTime ms
            ID: ${boundingBox.id}
            --------------------------
        """.trimIndent()

            // Append to the StringBuilder for display
            detectionTextBuilder.append(bboxText).append("\n")

            // Log the detection
            Log.d(TAG, """
            Detection ${index + 1} success:
            Normalized: x1: ${boundingBox.x1}, y1: ${boundingBox.y1}, x2: ${boundingBox.x2}, y2: ${boundingBox.y2}
            Pixels: x1: ${x1Pixels.toInt()}, y1: ${y1Pixels.toInt()}, x2: ${x2Pixels.toInt()}, y2: ${y2Pixels.toInt()}
            Confidence: ${boundingBox.cnf}, Inference time: $inferenceTime ms, ID: ${boundingBox.id}
        """.trimIndent())
        }

        // Display all detections in the text box
        boundingBoxTextView.text = detectionTextBuilder.toString()

        // Draw bounding box and center point on the image
        val processedBitmap = DetectionDrawer.drawDetection(bitmap, firstBox)
        Log.d(TAG, "Drawing bounding box and center point")

        // Display the processed image
        processedImageView.setImageBitmap(processedBitmap)

        Toast.makeText(this, "Detection success", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Detection success")
    }

    override fun onEmptyDetect() {
        "No object detected".also { boundingBoxTextView.text = it }
        Log.d(TAG, "No object detected")
    }

    override fun onDestroy() {
        super.onDestroy()
        tracker.close()
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
    }
}