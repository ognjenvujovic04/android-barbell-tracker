package com.ognjen.barbelltracker

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity(), Tracker.TrackerListener {

    private lateinit var originalVideoView: VideoView
    private lateinit var processedVideoView: VideoView
    private lateinit var overlayView: OverlayView
    private lateinit var boundingBoxTextView: TextView
    private lateinit var loadFromGalleryButton: Button
    private lateinit var detectButton: Button
    private lateinit var tracker: Tracker
    private var selectedVideoUri: Uri? = null

    // For frame extraction and processing
    private lateinit var retriever: MediaMetadataRetriever
    private val handler = Handler(Looper.getMainLooper())
    private var frameExtractorRunnable: Runnable? = null
    private var isProcessingVideo = false
    private var videoDuration = 0L
    private var currentPosition = 0L
    private val frameInterval = 100L // Extract frame every 100ms

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
        processedVideoView = findViewById(R.id.processedVideoView)
        overlayView = findViewById(R.id.overlay)
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
            processVideoRealtime()
        }

        // Setup video completion listener
        originalVideoView.setOnCompletionListener {
            // Reset video if we're processing frames
            if (isProcessingVideo) {
                originalVideoView.start()
            }
        }

        processedVideoView.setOnCompletionListener {
            // Reset video if we're processing frames
            if (isProcessingVideo) {
                processedVideoView.start()
                currentPosition = 0
            }
        }
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "Selected URI: $uri")
            selectedVideoUri = uri

            // Stop any ongoing processing
            stopVideoProcessing()

            // Set the video to both VideoViews
            originalVideoView.setVideoURI(uri)
            processedVideoView.setVideoURI(uri)

            // Clear overlay
            overlayView.clear()

            // Initialize retriever
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)

            // Get video duration
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDuration = durationString?.toLong() ?: 0L
        } else {
            Log.d(TAG, "No media selected")
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun processVideoRealtime() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            return
        }

        // If already processing, stop
        if (isProcessingVideo) {
            stopVideoProcessing()
            detectButton.text = "Detect"
            return
        }

        try {
            // Start both videos
            originalVideoView.start()
            processedVideoView.start()

            // Change button text
            detectButton.text = "Stop"

            // Start frame extraction
            isProcessingVideo = true
            currentPosition = 0
            startFrameExtraction()

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error processing video: ${e.message}", e)
            Toast.makeText(this, "Error processing video: ${e.message}", Toast.LENGTH_SHORT).show()
            stopVideoProcessing()
        }
    }

    private fun startFrameExtraction() {
        frameExtractorRunnable = object : Runnable {
            override fun run() {
                if (!isProcessingVideo || currentPosition > videoDuration) {
                    return
                }

                try {
                    // Get frame at current position
                    val timeMicrosec = currentPosition * 1000 // Convert to microseconds
                    val frameBitmap = retriever.getFrameAtTime(timeMicrosec,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.copy(Bitmap.Config.ARGB_8888, true)

                    if (frameBitmap != null) {
                        // Process frame with tracker
                        tracker.detect(frameBitmap)
                    }

                    // Update current position
                    currentPosition += frameInterval

                    // Schedule next frame extraction
                    handler.postDelayed(this, frameInterval)

                } catch (e: Exception) {
                    Log.e(ERRORTAG, "Error extracting frame: ${e.message}", e)
                }
            }
        }

        // Start the frame extraction process
        handler.post(frameExtractorRunnable!!)
    }

    private fun stopVideoProcessing() {
        isProcessingVideo = false

        // Remove callbacks
        frameExtractorRunnable?.let {
            handler.removeCallbacks(it)
        }

        // Release retriever if initialized
        if (::retriever.isInitialized) {
            retriever.release()
        }

        // Stop videos
        originalVideoView.stopPlayback()
        processedVideoView.stopPlayback()

        // Clear overlay
        overlayView.clear()

        // Reset button text
        detectButton.text = "Detect"
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (boundingBoxes.isEmpty()) {
            onEmptyDetect()
            return
        }

        // Update the overlay with detected bounding boxes
        runOnUiThread {
            overlayView.setResults(boundingBoxes)

            // StringBuilder to accumulate all detections for display in the text box
            val detectionTextBuilder = StringBuilder()

            // Log and display each detection with enumeration
            boundingBoxes.forEachIndexed { index, boundingBox ->
                // Format the detection information
                val bboxText = """
                Detection ${index + 1}:
                Normalized: x1: ${boundingBox.x1.format(4)}, y1: ${boundingBox.y1.format(4)},
                           x2: ${boundingBox.x2.format(4)}, y2: ${boundingBox.y2.format(4)}
                Confidence: ${boundingBox.cnf.format(4)}
                Inference time: $inferenceTime ms
                ID: ${boundingBox.id}
                --------------------------
                """.trimIndent()

                // Append to the StringBuilder for display
                detectionTextBuilder.append(bboxText).append("\n")
            }

            // Display all detections in the text box
            boundingBoxTextView.text = detectionTextBuilder.toString()
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            "No object detected".also { boundingBoxTextView.text = it }
            overlayView.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoProcessing()
        tracker.close()
    }

    override fun onPause() {
        super.onPause()
        stopVideoProcessing()
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
    }
}