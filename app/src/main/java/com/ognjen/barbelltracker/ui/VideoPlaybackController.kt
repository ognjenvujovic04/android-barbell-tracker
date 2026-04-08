package com.ognjen.barbelltracker.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import android.widget.VideoView
import com.ognjen.barbelltracker.domain.BarVelocitySample
import com.ognjen.barbelltracker.domain.BoundingBox
import kotlin.math.abs

class VideoPlaybackController(
    private val processedVideoView: VideoView,
    private val overlayView: OverlayView,
    private val speedGraphView: SpeedOverTimeGraphView,
    private val playButton: Button
) {

    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var isPlaying = false

    private var selectedVideoUri: Uri? = null
    private var trackingData: Map<Long, List<BoundingBox>> = emptyMap()

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
        speedGraphView.setSeries(data.values.toList())
    }

    fun clearTrackingData() {
        trackingData = emptyMap()
        speedGraphView.clear()
    }

    fun clearOverlay() {
        overlayView.clear()
        speedGraphView.clear()
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

    @SuppressLint("SetTextI18n")
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

        startTrackingPlayback()
    }

    @SuppressLint("SetTextI18n")
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
                speedGraphView.setPlaybackPositionMs(videoPosition)

                val closestTimestamp = findClosestTimestamp(videoPosition, trackingData.keys)
                val boxes = trackingData[closestTimestamp]

                if (!boxes.isNullOrEmpty()) {
                    overlayView.setResults(boxes)
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

    fun isPlaying(): Boolean {
        return isPlaying
    }

    fun cleanup() {
        stop()
        playbackRunnable?.let {
            handler.removeCallbacks(it)
        }
    }
}
