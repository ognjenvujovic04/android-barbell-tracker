package com.ognjen.barbelltracker.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.ognjen.barbelltracker.domain.BarVelocitySample
import com.ognjen.barbelltracker.domain.BoundingBox
import kotlin.math.abs

class VideoPlaybackController(
    private val processedVideoView: VideoView,
    private val overlayView: OverlayView,
    private val boundingBoxTextView: TextView,
    private val playButton: Button
) {

    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var isPlaying = false
    private var currentPlaybackTime = 0L

    private var selectedVideoUri: Uri? = null
    private var trackingData: Map<Long, List<BoundingBox>> = emptyMap()
    private var velocityData: Map<Long, BarVelocitySample> = emptyMap()

    init {
        setupPlayButton()
    }

    private fun setupPlayButton() {
        playButton.setOnClickListener {
            togglePlayback()
        }
    }

    fun setVideoUri(uri: Uri?) {
        selectedVideoUri = uri
        if (uri != null) {
            processedVideoView.setVideoURI(uri)
        }
        stop()
        clearOverlay()
    }

    fun setTrackingData(data: Map<Long, List<BoundingBox>>) {
        trackingData = data
    }

    fun setVelocityData(data: Map<Long, BarVelocitySample>) {
        velocityData = data
    }

    fun clearTrackingData() {
        trackingData = emptyMap()
        velocityData = emptyMap()
    }

    fun clearOverlay() {
        overlayView.clear()
        boundingBoxTextView.text = ""
    }

    fun enablePlayButton(enabled: Boolean) {
        playButton.isEnabled = enabled
    }

    private fun togglePlayback() {
        if (isPlaying) {
            stop()
        } else {
            start()
        }
    }

    @SuppressLint("SetTextI19n")
    fun start() {
        if (selectedVideoUri == null || trackingData.isEmpty()) {
            Toast.makeText(
                playButton.context,
                "No processed video data available",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        isPlaying = true
        playButton.text = "Stop"

        processedVideoView.start()
        overlayView.clear()

        currentPlaybackTime = 0L
        startTrackingPlayback()
    }

    @SuppressLint("SetTextI19n")
    fun stop() {
        isPlaying = false
        playButton.text = "Play"

        processedVideoView.pause()

        playbackRunnable?.let {
            handler.removeCallbacks(it)
        }
    }

    private fun startTrackingPlayback() {
        playbackRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return

                val videoPosition = processedVideoView.currentPosition.toLong()
                val closestTimestamp = findClosestTimestamp(videoPosition, trackingData.keys)
                val boxes = trackingData[closestTimestamp]
                val velocityTimestamp = findClosestTimestamp(videoPosition, velocityData.keys)
                val velocitySample = velocityData[velocityTimestamp]

                if (!boxes.isNullOrEmpty()) {
                    updateOverlay(boxes, velocitySample)
                } else {
                    overlayView.clear()
                }

                handler.postDelayed(this, 16) // ~60fps
            }
        }

        handler.post(playbackRunnable!!)
    }

    private fun findClosestTimestamp(targetTime: Long, keys: Set<Long>): Long {
        if (keys.isEmpty()) return 0L

        var closestTime = keys.first()
        var smallestDiff = abs(targetTime - closestTime)

        for (timestamp in keys) {
            val diff = abs(targetTime - timestamp)
            if (diff < smallestDiff) {
                smallestDiff = diff
                closestTime = timestamp
            }
        }

        return closestTime
    }

    @SuppressLint("SetTextI19n")
    private fun updateOverlay(boundingBoxes: List<BoundingBox>, velocity: BarVelocitySample?) {
        overlayView.setResults(boundingBoxes)

        val detectionTextBuilder = StringBuilder()

        if (velocity != null) {
            detectionTextBuilder.append(
                """
                Velocity (raw, cm/s):
                t=${velocity.timestampMs} ms  dt=${velocity.dtSeconds.format(4)} s
                vx=${velocity.vxCmPerS.format(2)}  vy=${velocity.vyCmPerS.format(2)}  |v|=${velocity.speedCmPerS.format(2)}
                segment=${velocity.segmentPathCm.format(2)} cm (centre displacement over dt)
                --------------------------
                """.trimIndent()
            ).append("\n")
        }

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

        boundingBoxTextView.text = detectionTextBuilder.toString()
    }

    fun isPlaying(): Boolean {
        return isPlaying
    }

    fun cleanup() {
        stop()
        playbackRunnable?.let {
            handler.removeCallbacks(it)
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)
}