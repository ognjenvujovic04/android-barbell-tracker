package com.ognjen.barbelltracker

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var originalImageView: ImageView
    private lateinit var processedImageView: ImageView
    private lateinit var loadFromGalleryButton: Button
    private lateinit var detectButton: Button

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            Log.d("BarbellpathLog", "Selected URI: $uri")
            originalImageView.setImageURI(uri) // Set the image to the ImageView
        } else {
            Log.d("BarbellpathLog", "No media selected")
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        originalImageView = findViewById(R.id.originalImageView)
        processedImageView = findViewById(R.id.processedImageView)
        loadFromGalleryButton = findViewById(R.id.loadFromGalleryButton)
        detectButton = findViewById(R.id.detectButton)

        // Set button click listeners
        loadFromGalleryButton.setOnClickListener {
            openGallery()
        }

        detectButton.setOnClickListener {
            // Todo process the image and detect the barbell
            // For now, just show a toast
            Toast.makeText(this, "Detection not implemented yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}