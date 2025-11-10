package com.ognjen.barbelltracker.ui

import android.annotation.SuppressLint
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
import androidx.constraintlayout.widget.ConstraintLayout
import com.ognjen.barbelltracker.R
import com.ognjen.barbelltracker.domain.VideoProcessor
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

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
    private lateinit var textViewPopup: TextView

    private lateinit var videoProcessor: VideoProcessor
    private lateinit var barbellSelectionPopup: BarbellSelectionPopup
    private lateinit var selectionOverlayView: SelectionOverlayView
    private lateinit var videoPlaybackController: VideoPlaybackController

    private var selectedVideoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeOpenCV()
        initializeViews()
        initializeComponents()
        setupObservers()
        setupButtonListeners()
        setDefaultVideoFromAssets()
    }

    private fun initializeOpenCV() {
        if (!OpenCVLoader.initDebug()) {
            Log.d(ERRORTAG, "Unable to load OpenCV")
        } else {
            Log.d(TAG, "OpenCV loaded")
        }
    }

    private fun initializeViews() {
        // Main views
        processedVideoView = findViewById(R.id.processedVideoView)
        overlayView = findViewById(R.id.overlay)
        boundingBoxTextView = findViewById(R.id.boundingBoxTextView)
        loadFromGalleryButton = findViewById(R.id.loadFromGalleryButton)
        processButton = findViewById(R.id.detectButton)
        playButton = findViewById(R.id.playButton)

        // Popup views
        popupContainer = findViewById(R.id.popupContainer)
        selectionOverlayView = findViewById(R.id.overlaySelectionView)
        button1 = findViewById(R.id.button1)
        button2 = findViewById(R.id.button2)
        firstFrameView = findViewById(R.id.firstFrameView)
        textViewPopup = findViewById(R.id.textViewPopup)
    }

    private fun initializeComponents() {
        // Initialize VideoProcessor
        videoProcessor = VideoProcessor(this, "models/best_320_float32.tflite")

        // Initialize VideoPlaybackController
        videoPlaybackController = VideoPlaybackController(
            processedVideoView,
            overlayView,
            boundingBoxTextView,
            playButton
        )

        // Initialize BarbellSelectionPopup
        barbellSelectionPopup = BarbellSelectionPopup(
            this,
            videoProcessor,
            popupContainer,
            button1,
            button2,
            firstFrameView,
            textViewPopup,
            selectionOverlayView
        )

        // Set popup listeners
        barbellSelectionPopup.setOnBarbellSelectedListener {
            processVideo()
        }

        barbellSelectionPopup.setOnCancelListener {
            // Optional: Handle cancel action
        }
    }

    @SuppressLint("SetTextI19n", "SetTextI18n")
    private fun setupObservers() {
        videoProcessor.processingStatusLiveData.observe(this) { status ->
            when (status) {
                is VideoProcessor.ProcessingStatus.STARTING -> {
                    processButton.text = "Cancel Processing"
                    videoPlaybackController.enablePlayButton(false)
                }
                is VideoProcessor.ProcessingStatus.PROCESSING -> {
                    // Status already handled by progress updates
                }
                is VideoProcessor.ProcessingStatus.COMPLETED -> {
                    processButton.text = "Process Video"
                    videoPlaybackController.enablePlayButton(true)

                    // Get tracking data and pass it to playback controller
                    val trackingData = videoProcessor.getTrackingData()
                    overlayView.setSelectedBarbellId(videoProcessor.selectedBarbellId)
                    videoPlaybackController.setTrackingData(trackingData)

                    Toast.makeText(
                        this,
                        "Processing complete! ${trackingData.size} frames processed",
                        Toast.LENGTH_SHORT
                    ).show()

                    videoPlaybackController.start()
                }
                is VideoProcessor.ProcessingStatus.CANCELLED -> {
                    processButton.text = "Process Video"
                    videoPlaybackController.enablePlayButton(false)
                    Toast.makeText(this, "Processing cancelled", Toast.LENGTH_SHORT).show()
                }
                is VideoProcessor.ProcessingStatus.ERROR -> {
                    processButton.text = "Process Video"
                    videoPlaybackController.enablePlayButton(false)
                    Toast.makeText(this, "Error: ${status.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupButtonListeners() {
        loadFromGalleryButton.setOnClickListener {
            openGallery()
        }

        processButton.setOnClickListener {
            if (selectedVideoUri != null) {
                barbellSelectionPopup.show(selectedVideoUri!!)
            } else {
                Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "Selected URI: $uri")
            selectedVideoUri = uri

            // Stop any ongoing processing or playback
            stopProcessingAndPlayback()

            // Set the video to playback controller
            videoPlaybackController.setVideoUri(uri)

            // Reset tracking data
            videoPlaybackController.clearTrackingData()

            // Enable process button
            processButton.isEnabled = true
        } else {
            Log.d(TAG, "No media selected")
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
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

    private fun stopProcessingAndPlayback() {
        // Stop video processing if in progress
        videoProcessor.stopProcessing()

        // Stop playback and clear overlay
        videoPlaybackController.stop()
        videoPlaybackController.clearOverlay()
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

            // Set the video to playback controller
            videoPlaybackController.setVideoUri(uri)

            // Enable process button
            processButton.isEnabled = true

            Toast.makeText(this, "Default video loaded: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error loading default video from assets: ${e.message}")
            Toast.makeText(this, "Failed to load default video", Toast.LENGTH_LONG).show()
            processButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessingAndPlayback()
        videoPlaybackController.cleanup()
        videoProcessor.close()
    }

    override fun onPause() {
        super.onPause()
        videoPlaybackController.stop()
    }

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
    }
}