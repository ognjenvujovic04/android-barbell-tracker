package com.ognjen.barbelltracker.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.ognjen.barbelltracker.domain.BoundingBox
import com.ognjen.barbelltracker.domain.VideoProcessingResult
import kotlin.math.abs
import kotlin.math.min

class VideoPlaybackController(
    private val processedVideoView: VideoView,
    private val overlayView: OverlayView,
    private val speedGraphView: SpeedOverTimeGraphView,
    private val movementPhaseStripView: MovementPhaseStripView,
    private val phaseMetricsText: TextView?,
    private val playButton: Button
) {

    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var isPlaying = false

    private var selectedVideoUri: Uri? = null
    private var trackingData: Map<Long, List<BoundingBox>> = emptyMap()

    /** From last [VideoView.setOnPreparedListener]; used to re-fit after layout. */
    private var preparedVideoWidth: Int = 0
    private var preparedVideoHeight: Int = 0

    private val videoContainer: FrameLayout?
        get() = processedVideoView.parent as? FrameLayout

    private var containerLayoutListener: View.OnLayoutChangeListener? = null

    init {
        setupPlayButton()
    }

    private fun setupPlayButton() {
        playButton.setOnClickListener {
            togglePlayback()
        }
    }

    
    private fun applyCenteredFitVideoSize() {
        val parent = videoContainer ?: return
        val vw = preparedVideoWidth
        val vh = preparedVideoHeight
        if (vw <= 0 || vh <= 0) return
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return
        val scale = min(pw.toFloat() / vw, ph.toFloat() / vh)
        val w = (vw * scale).toInt().coerceAtLeast(1)
        val h = (vh * scale).toInt().coerceAtLeast(1)
        val lp = FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.CENTER
        }
        processedVideoView.layoutParams = lp
        overlayView.setSourceVideoSize(vw, vh)
    }

    fun setVideoUri(uri: Uri?) {
        selectedVideoUri = uri
        preparedVideoWidth = 0
        preparedVideoHeight = 0
        videoContainer?.let { c ->
            containerLayoutListener?.let { c.removeOnLayoutChangeListener(it) }
        }
        containerLayoutListener = null

        if (uri != null) {
            processedVideoView.setVideoURI(uri)
            processedVideoView.setOnPreparedListener { mp ->
                preparedVideoWidth = mp.videoWidth
                preparedVideoHeight = mp.videoHeight
                processedVideoView.post {
                    applyCenteredFitVideoSize()
                }
                val container = videoContainer
                if (container != null) {
                    containerLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        if (preparedVideoWidth > 0 && preparedVideoHeight > 0) {
                            applyCenteredFitVideoSize()
                        }
                    }
                    container.addOnLayoutChangeListener(containerLayoutListener!!)
                }
            }
        } else {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            processedVideoView.layoutParams = lp
        }
        stop()
        clearOverlay()
    }

    fun setTrackingData(data: Map<Long, List<BoundingBox>>) {
        trackingData = data
    }

    /**
     * Feed the precomputed [VideoProcessingResult] from [com.ognjen.barbelltracker.domain.VideoProcessor]
     * into the UI. No additional analysis is performed here — this is the same shape a React Native
     * bridge would receive.
     */
    fun setProcessingResult(result: VideoProcessingResult) {
        trackingData = result.trackingData
        speedGraphView.setSeries(result.velocitySamples.values.toList())
        if (result.phaseSegments.isEmpty()) {
            movementPhaseStripView.clear()
            hidePhaseMetrics()
            return
        }
        movementPhaseStripView.setPhaseData(
            result.phaseSegments,
            result.startTimestampMs,
            result.endTimestampMs
        )
        updatePhaseMetrics(result)
    }

    fun clearTrackingData() {
        trackingData = emptyMap()
        speedGraphView.clear()
        movementPhaseStripView.clear()
        hidePhaseMetrics()
    }

    fun clearOverlay() {
        overlayView.clear()
        speedGraphView.clear()
        movementPhaseStripView.clear()
        hidePhaseMetrics()
    }

    private fun hidePhaseMetrics() {
        phaseMetricsText?.visibility = View.GONE
    }

    private fun updatePhaseMetrics(result: VideoProcessingResult) {
        val tv = phaseMetricsText ?: return
        if (result.phaseSegments.isEmpty()) {
            tv.visibility = View.GONE
            return
        }
        val upS = result.upDurationMs / 1000f
        val downS = result.downDurationMs / 1000f
        val holdS = result.stationaryDurationMs / 1000f
        tv.text = "Reps: ${result.repCount} · Up %.1f s · Down %.1f s · Hold %.1f s"
            .format(upS, downS, holdS)
        tv.visibility = View.VISIBLE
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
                movementPhaseStripView.setPlaybackPositionMs(videoPosition)

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
        videoContainer?.let { c ->
            containerLayoutListener?.let { c.removeOnLayoutChangeListener(it) }
        }
        containerLayoutListener = null
    }
}
