package com.ognjen.barbelltracker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A class that processes videos to track barbell paths using the Tracker class.
 * It extracts frames from a video at the video's native frame rate, processes them
 * with object detection, and stores tracking data in a timestamp-based map for efficient playback.
 */
class VideoProcessor(
    private val context: Context,
    private val modelPath: String
) : Tracker.TrackerListener {

    // For frame extraction
    private lateinit var retriever: MediaMetadataRetriever
    private val executorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracking data - maps time (milliseconds) to detected bounding boxes
    private val trackingData = mutableMapOf<Long, List<BoundingBox>>()

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private var videoDuration: Long = 0
    private var processedFrames: Int = 0
    private var totalFramesToProcess: Int = 0
    private var frameIntervalMs: Long = 100L

    // Progress tracking and callbacks
    private val _progressLiveData = MutableLiveData<Int>()
    val progressLiveData: LiveData<Int> = _progressLiveData

    private val _processingStatusLiveData = MutableLiveData<ProcessingStatus>()
    val processingStatusLiveData: LiveData<ProcessingStatus> = _processingStatusLiveData

    // Tracker instance
    private var tracker: Tracker? = null

    /**
     * Process a video file and extract barbell tracking data at the video's native frame rate
     *
     * @param videoUri Uri of the video to process
     * @return Map of timestamps to bounding boxes after processing is complete
     */
    fun processVideo(videoUri: Uri): Map<Long, List<BoundingBox>> {
        if (isProcessing.get()) {
            Log.w(TAG, "Already processing a video, cannot start another process")
            return emptyMap()
        }

        // Reset state
        trackingData.clear()
        processedFrames = 0
        _progressLiveData.postValue(0)
        _processingStatusLiveData.postValue(ProcessingStatus.STARTING)

        try {
            // Initialize tracker if needed
            if (tracker == null) {
                tracker = Tracker(context, modelPath, this)
            }

            // Initialize media retriever
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)

            // Get video duration in milliseconds
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDuration = durationString?.toLong() ?: 0L

            if (videoDuration <= 0) {
                _processingStatusLiveData.postValue(ProcessingStatus.ERROR("Invalid video duration"))
                return emptyMap()
            }

            // Determine native frame rate and set interval
            val fpsString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsString?.toFloatOrNull() ?: 30f
            frameIntervalMs = (1000 / fps).toLong().coerceAtLeast(1L)

            // Calculate total frames to process for progress reporting
            totalFramesToProcess = (videoDuration / frameIntervalMs).toInt() + 1

            // Start processing in background
            isProcessing.set(true)
            _processingStatusLiveData.postValue(ProcessingStatus.PROCESSING)

            executorService.execute {
                processFrames()
            }

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error initializing video processing: ${e.message}", e)
            _processingStatusLiveData.postValue(ProcessingStatus.ERROR(e.message ?: "Unknown error"))
            releaseResources()
        }

        return trackingData
    }

    /**
     * Extract and process frames from the video at the native frame rate
     */
    private fun processFrames() {
        try {
            var currentPosition = 0L

            while (isProcessing.get() && currentPosition <= videoDuration) {
                // Convert ms to microseconds for retriever
                val timeUs = currentPosition * 1000
                val frameBitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )?.copy(Bitmap.Config.ARGB_8888, true)

                if (frameBitmap != null) {
                    val timestamp = currentPosition
                    val bitmapForProcessing = frameBitmap

                    mainHandler.post {
                        tracker?.detect(bitmapForProcessing)

                        // Progress update
                        processedFrames++
                        val progress = (processedFrames * 100) / totalFramesToProcess
                        _progressLiveData.postValue(progress)

                        // Release bitmap
                        bitmapForProcessing.recycle()
                    }

                    // Small sleep to prevent OOM
                    Thread.sleep(5)
                }

                currentPosition += frameIntervalMs
            }

            // Allow final callbacks to complete
            Thread.sleep(100)

            mainHandler.post {
                if (isProcessing.get()) {
                    _processingStatusLiveData.postValue(ProcessingStatus.COMPLETED)
                    releaseResources()
                }
            }

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error processing video frames: ${e.message}", e)
            mainHandler.post {
                _processingStatusLiveData.postValue(ProcessingStatus.ERROR(e.message ?: "Unknown error"))
                releaseResources()
            }
        }
    }

    /**
     * Stop processing if currently running
     */
    fun stopProcessing() {
        if (isProcessing.get()) {
            isProcessing.set(false)
            _processingStatusLiveData.postValue(ProcessingStatus.CANCELLED)
            releaseResources()
        }
    }

    /**
     * Get the tracking data map. This contains timestamp-to-bounding-box mappings.
     */
    fun getTrackingData(): Map<Long, List<BoundingBox>> {
        return trackingData.toMap()
    }

    /**
     * Release resources when processing is complete
     */
    private fun releaseResources() {
        isProcessing.set(false)
        try {
            if (::retriever.isInitialized) {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error releasing resources: ${e.message}", e)
        }
    }

    /**
     * Clean up resources when the processor is no longer needed
     */
    fun close() {
        stopProcessing()
        tracker?.close()
        tracker = null
        executorService.shutdown()
    }

    /**
     * Callback from Tracker when object detection yields results
     */
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (isProcessing.get()) {
            val timestamp = processedFrames * frameIntervalMs
            trackingData[timestamp] = boundingBoxes
            Log.d(TAG, "Timestamp $timestamp: ${boundingBoxes.size} detections, inference time: $inferenceTime ms, boxes: $boundingBoxes")
        }
    }

    /**
     * Callback from Tracker when no objects are detected
     */
    override fun onEmptyDetect() {
        val timestamp = processedFrames * frameIntervalMs
        trackingData[timestamp] = emptyList()
    }

    /**
     * Status class to represent the current state of video processing
     */
    sealed class ProcessingStatus {
        object STARTING : ProcessingStatus()
        object PROCESSING : ProcessingStatus()
        object COMPLETED : ProcessingStatus()
        object CANCELLED : ProcessingStatus()
        data class ERROR(val message: String) : ProcessingStatus()
    }

    companion object {
        private const val TAG = "VideoProcessor"
        private const val ERRORTAG = "VideoProcessorError"
    }
}
