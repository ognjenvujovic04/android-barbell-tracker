package com.ognjen.barbelltracker.ui

import android.net.Uri
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        applyTopSystemInsetsToControls()

        initializeOpenCV()
        initializeViews()
        initializeComponents()
        setupButtonListeners()
        setDefaultVideoFromAssets()
    }

    private fun applyTopSystemInsetsToControls() {
        val controls = findViewById<View>(R.id.buttonLayout)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val topInsetTypes =
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            val safe = insets.getInsets(topInsetTypes)
            view.updatePadding(top = safe.top, left = safe.left, right = safe.right)
            insets
        }
        ViewCompat.requestApplyInsets(controls)
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
        val uri = selectedVideoUri
        if (uri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            return
        }

        processButton.text = "Processing..."
        videoPlaybackController.enablePlayButton(false)

        videoProcessor.processVideoAsync(
            uri = uri,
            onProgress = { progress ->
                // Optional: show progress percentage
                runOnUiThread {
                    processButton.text = "Processing $progress%"
                }
            },
            onComplete = { trackingData ->
                runOnUiThread {
                    processButton.text = "Process Video"
                    videoPlaybackController.enablePlayButton(true)

                    overlayView.setSelectedBarbellId(videoProcessor.selectedBarbellId)
                    videoPlaybackController.setTrackingData(trackingData)
                    videoPlaybackController.start()

                    Toast.makeText(
                        this,
                        "Processing complete! ${trackingData.size} frames processed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    processButton.text = "Process Video"
                    videoPlaybackController.enablePlayButton(false)
                    Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
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