package com.ognjen.barbelltracker

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val originalImageView: ImageView = findViewById(R.id.originalImageView)
        val processedImageView: ImageView = findViewById(R.id.processedImageView)
        val detectButton: Button = findViewById(R.id.detectButton)

        originalImageView.setImageResource(R.drawable.test)

        detectButton.setOnClickListener {
            processedImageView.setImageResource(R.drawable.test)
        }
    }
}