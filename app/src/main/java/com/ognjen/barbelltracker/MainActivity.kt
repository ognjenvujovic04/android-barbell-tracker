package com.ognjen.barbelltracker

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), Tracker.TrackerListener {

    private lateinit var originalImageView: ImageView
    private lateinit var processedImageView: ImageView
    private lateinit var boundingBoxTextView: TextView
    private lateinit var loadFromGalleryButton: Button
    private lateinit var detectButton: Button
    private lateinit var tracker: Tracker

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            Log.d("BarbelltrackerLog", "Selected URI: $uri")
            originalImageView.setImageURI(uri) // Set the image to the ImageView
        } else {
            Log.d("BarbelltrackerLog", "No media selected")
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        originalImageView = findViewById(R.id.originalImageView)
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
            processImage()
        }
    }

    private fun openGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun processImage() {
        val drawable = originalImageView.drawable as? BitmapDrawable
        val bitmap = drawable?.bitmap

        if (bitmap == null) {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
            return
        }

        tracker.detect(bitmap)
    }

    override fun onTrack(boundingBox: BoundingBox, inferenceTime: Long) {
        // Get original image dimensions
        val drawable = originalImageView.drawable as? BitmapDrawable
        val bitmap = drawable?.bitmap ?: return
        val origWidth = bitmap.width
        val origHeight = bitmap.height

        // Convert normalized coordinates to absolute pixels for display
        val x1Pixels = boundingBox.x1 * origWidth
        val y1Pixels = boundingBox.y1 * origHeight
        val x2Pixels = boundingBox.x2 * origWidth
        val y2Pixels = boundingBox.y2 * origHeight

        // Display both normalized and pixel values for debugging
        val bboxText = """
        Normalized: x1: ${boundingBox.x1.format(4)}, y1: ${boundingBox.y1.format(4)}, 
                   x2: ${boundingBox.x2.format(4)}, y2: ${boundingBox.y2.format(4)}
        Pixels: x1: ${x1Pixels.toInt()}, y1: ${y1Pixels.toInt()}, 
               x2: ${x2Pixels.toInt()}, y2: ${y2Pixels.toInt()}
        Confidence: ${boundingBox.cnf.format(4)}
        Inference time: $inferenceTime ms
    """.trimIndent()

        boundingBoxTextView.text = bboxText

        Log.d("BarbelltrackerLog", """
        Detection success:
        Normalized: x1: ${boundingBox.x1}, y1: ${boundingBox.y1}, x2: ${boundingBox.x2}, y2: ${boundingBox.y2}
        Pixels: x1: ${x1Pixels.toInt()}, y1: ${y1Pixels.toInt()}, x2: ${x2Pixels.toInt()}, y2: ${y2Pixels.toInt()}
        Confidence: ${boundingBox.cnf}, Inference time: $inferenceTime ms
    """.trimIndent())

        Toast.makeText(this, "Detection success", Toast.LENGTH_SHORT).show()

        // todo draw bounding box on processedImageView
    }


    override fun onNoTrack() {
        boundingBoxTextView.text = "No object detected"
        Log.d("BarbelltrackerLog", "No object detected")
    }

    override fun onDestroy() {
        super.onDestroy()
        tracker.close()
    }

    fun Float.format(digits: Int) = "%.${digits}f".format(this)
}
