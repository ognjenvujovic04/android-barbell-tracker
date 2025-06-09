package com.ognjen.barbelltracker

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var processedVideoView: VideoView
    private lateinit var overlayView: OverlayView
    private lateinit var boundingBoxTextView: TextView
    private lateinit var loadFromGalleryButton: Button
    private lateinit var processButton: Button
    private lateinit var playButton: Button

    private lateinit var popupContainer: ConstraintLayout
    private lateinit var button1: Button
    private lateinit var button2: Button
    private lateinit var firstFrameView: ImageView

    private lateinit var videoProcessor: VideoProcessor
    private var selectedVideoUri: Uri? = null
    private var trackingData: Map<Long, List<BoundingBox>> = emptyMap()

    // For playback
    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var isPlaying = false
    private var currentPlaybackTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Try to load OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.d(ERRORTAG, "Unable to load OpenCV")
        } else {
            Log.d(TAG, "OpenCV loaded")
        }

        // Initialize popup views
        popupContainer = findViewById(R.id.popupContainer)
        button1 = findViewById(R.id.button1)
        button2 = findViewById(R.id.button2)
        firstFrameView = findViewById(R.id.firstFrameView)

        // Initialize views
        processedVideoView = findViewById(R.id.processedVideoView)
        overlayView = findViewById(R.id.overlay)
        boundingBoxTextView = findViewById(R.id.boundingBoxTextView)
        loadFromGalleryButton = findViewById(R.id.loadFromGalleryButton)
        processButton = findViewById(R.id.detectButton) // Reusing the detect button for processing
        playButton = findViewById(R.id.playButton) // You'll need to add this to your layout

        // Initialize VideoProcessor
        videoProcessor = VideoProcessor(this, "best_320_float32.tflite")

        // Set the default video from assets
        setDefaultVideoFromAssets()

        // Observe processing status and progress
        setupObservers()

        // Set button click listeners
        loadFromGalleryButton.setOnClickListener {
            openGallery()
        }

        processButton.setOnClickListener {
            showPopupVideo()
        }

        // Set click listeners for popup buttons
        button1.setOnClickListener {
            Toast.makeText(this, "Button 1 clicked", Toast.LENGTH_SHORT).show()
            hidePopupVideo()
            processVideo()
        }

        button2.setOnClickListener {
            hidePopupVideo()
        }

        playButton.setOnClickListener {
            togglePlayback()
        }

    }

    private fun showPopupVideo() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Show the popup
        popupContainer.visibility = View.VISIBLE

        // Selecting barbell to be tracked
        barbellSelection()

    }

    private fun barbellSelection() {
        try
        {
            val firstFrame = videoProcessor.firstFrame(selectedVideoUri!!)

            firstFrameView.setImageBitmap(firstFrame)
        }catch (e: Exception) {
            Log.e(ERRORTAG, "Error extracting first frame: ${e.message}")
            Toast.makeText(this, "Failed to extract first frame", Toast.LENGTH_LONG).show()

        }
    }

    private fun hidePopupVideo() {
        popupContainer.visibility = View.GONE
    }

    @SuppressLint("SetTextI19n", "SetTextI18n")
    private fun setupObservers() {

        // Observe processing status
        videoProcessor.processingStatusLiveData.observe(this) { status ->
            when (status) {
                is VideoProcessor.ProcessingStatus.STARTING -> {
                    processButton.text = "Cancel Processing"
                    playButton.isEnabled = false
                }
                is VideoProcessor.ProcessingStatus.PROCESSING -> {
                    // Status already handled by progress updates
                }
                is VideoProcessor.ProcessingStatus.COMPLETED -> {
                    processButton.text = "Process Video"
                    playButton.isEnabled = true

                    // Get tracking data
                    trackingData = videoProcessor.getTrackingData()
                    Toast.makeText(this, "Processing complete! ${trackingData.size} frames processed",
                        Toast.LENGTH_SHORT).show()
                    togglePlayback()
                }
                is VideoProcessor.ProcessingStatus.CANCELLED -> {
                    processButton.text = "Process Video"
                    playButton.isEnabled = false
                    Toast.makeText(this, "Processing cancelled", Toast.LENGTH_SHORT).show()
                }
                is VideoProcessor.ProcessingStatus.ERROR -> {
                    processButton.text = "Process Video"
                    playButton.isEnabled = false
                    Toast.makeText(this, "Error: ${status.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "Selected URI: $uri")
            selectedVideoUri = uri

            // Stop any ongoing processing or playback
            stopProcessingAndPlayback()

            // Set the video to VideoViews
            processedVideoView.setVideoURI(uri)

            // Clear overlay
            overlayView.clear()

            // Reset tracking data
            trackingData = emptyMap()

            // Enable process button
            processButton.isEnabled = true
            playButton.isEnabled = false
        } else {
            Log.d(TAG, "No media selected")
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
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

        // If already processing, stop
        if (videoProcessor.processingStatusLiveData.value is VideoProcessor.ProcessingStatus.PROCESSING) {
            videoProcessor.stopProcessing()
            return
        }

        // Start processing the video
        videoProcessor.processVideo(selectedVideoUri!!)
    }

    private fun togglePlayback() {
        if (isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    @SuppressLint("SetTextI19n", "SetTextI18n")
    private fun startPlayback() {
        if (selectedVideoUri == null || trackingData.isEmpty()) {
            Toast.makeText(this, "No processed video data available", Toast.LENGTH_SHORT).show()
            return
        }

        isPlaying = true
        playButton.text = "Stop"

        // Start  videos
        processedVideoView.start()

        // Reset overlay
        overlayView.clear()

        // Start tracking playback
        currentPlaybackTime = 0L
        startTrackingPlayback()
    }

    @SuppressLint("SetTextI19n", "SetTextI18n")
    private fun stopPlayback() {
        isPlaying = false
        playButton.text = "Play"

        processedVideoView.pause()

        // Remove playback callback
        playbackRunnable?.let {
            handler.removeCallbacks(it)
        }
    }

    private fun startTrackingPlayback() {
        playbackRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return

                // Get current video position
                val videoPosition = processedVideoView.currentPosition.toLong()

                // Find the closest timestamp in trackingData
                val closestTimestamp = findClosestTimestamp(videoPosition)

                // Get bounding boxes for this timestamp
                val boxes = trackingData[closestTimestamp]

                // Update overlay with bounding boxes
                if (!boxes.isNullOrEmpty()) {
                    updateOverlay(boxes)
                } else {
                    overlayView.clear()
                }

                // Schedule next update
                handler.postDelayed(this, 16) // ~60fps
            }
        }

        // Start playback tracking
        handler.post(playbackRunnable!!)
    }

    private fun findClosestTimestamp(targetTime: Long): Long {
        if (trackingData.isEmpty()) return 0L

        // Find timestamp closest to the target time
        var closestTime = trackingData.keys.first()
        var smallestDiff = abs(targetTime - closestTime)

        for (timestamp in trackingData.keys) {
            val diff = abs(targetTime - timestamp)
            if (diff < smallestDiff) {
                smallestDiff = diff
                closestTime = timestamp
            }
        }

        return closestTime
    }

    @SuppressLint("SetTextI19n")
    private fun updateOverlay(boundingBoxes: List<BoundingBox>) {
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
            ID: ${boundingBox.id}
            --------------------------
            """.trimIndent()

            // Append to the StringBuilder for display
            detectionTextBuilder.append(bboxText).append("\n")
        }

        // Display all detections in the text box
        boundingBoxTextView.text = detectionTextBuilder.toString()
    }

    private fun stopProcessingAndPlayback() {
        // Stop video processing if in progress
        videoProcessor.stopProcessing()

        // Stop playback
        stopPlayback()

        // Clear overlay
        overlayView.clear()
    }

    private fun setDefaultVideoFromAssets() {
        try {
            val fileName = "test_video.mp4"
            val inputStream: InputStream = assets.open(fileName)
            val file = File(cacheDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()

            val uri = Uri.fromFile(file)
            selectedVideoUri = uri

            // Set the video to VideoViews
            processedVideoView.setVideoURI(uri)

            // Enable process button
            processButton.isEnabled = true
            playButton.isEnabled = false

            Toast.makeText(this, "Default video loaded: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error loading default video from assets: ${e.message}")
            Toast.makeText(this, "Failed to load default video", Toast.LENGTH_LONG).show()
            processButton.isEnabled = false
            playButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingAndPlayback()
        videoProcessor.close()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
    }
}