package com.ognjen.barbelltracker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A class that processes videos to track barbell paths using the BarbellDetector class.
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
    private var reusableBitmap: Bitmap? = null


    // Tracking data - maps time (milliseconds) to detected bounding boxes
    private val trackingData = mutableMapOf<Long, List<BoundingBox>>()

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private var videoDuration: Long = 0
    private var processedFrames: Int = 0
    private var totalFramesToProcess: Int = 0
    private var frameIntervalMs: Long = 100L
    private var currentTimestamp: Long = 0L


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
            Log.d(TAG, "Video duration: $videoDuration ms")

            if (videoDuration <= 0) {
                _processingStatusLiveData.postValue(ProcessingStatus.ERROR("Invalid video duration"))
                return emptyMap()
            }

            // Determine native frame rate and set interval
            val fpsString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            Log.d(TAG, "Native frame rate: $fpsString fps")
            val fps = fpsString?.toFloatOrNull() ?: 20f
            frameIntervalMs = (1000 / fps).toLong().coerceAtLeast(1L)
            Log.d(TAG, "FPS: $fps, Frame interval: $frameIntervalMs ms")

            // height and width of the video
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            Log.d(TAG, "Video dimensions: ${videoWidth}x${videoHeight}")

            // Create a reusable bitmap for processing frames
            reusableBitmap = Bitmap.createBitmap(videoHeight, videoWidth, Bitmap.Config.ARGB_8888)


            // Calculate total frames to process for progress reporting
            totalFramesToProcess = (videoDuration / frameIntervalMs).toInt() + 1

            // Start processing in background
            isProcessing.set(true)
            _processingStatusLiveData.postValue(ProcessingStatus.PROCESSING)

            executorService.execute {
                var inferenceTime = SystemClock.uptimeMillis()
                processFrames()
                inferenceTime = SystemClock.uptimeMillis() - inferenceTime
                Log.d(TAG, "Total processing time: $inferenceTime ms")
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

                // Use OPTION_CLOSEST to get the exact frame at the timestamp
                val frameBitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (frameBitmap != null) {
                    try {
                        currentTimestamp = currentPosition
                        // Create a copy with the right config for processing
                        reusableBitmap = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)

                        // Process frame synchronously to ensure proper sequencing
                        if (isProcessing.get()) {
                            tracker?.detect(reusableBitmap ?: return)
                        }
                    } finally {
                        reusableBitmap?.recycle()
                        frameBitmap.recycle()
                    }

                    // Small sleep to prevent OOM and allow callbacks to complete
                    Thread.sleep(10)
                } else {
                    Log.w(TAG, "Failed to extract frame at ${currentPosition}ms")
                    // If frame extraction fails, still record empty detection for this timestamp
                    currentTimestamp = currentPosition
                    onEmptyDetect()
                }

                currentPosition += frameIntervalMs
                processedFrames++

            }

            // Allow final callbacks to complete
            Thread.sleep(50)

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
     * Callback from BarbellDetector when object detection yields results
     */
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (isProcessing.get()) {
            trackingData[currentTimestamp] = boundingBoxes
        }
    }

    /**
     * Callback from BarbellDetector when no objects are detected
     */
    override fun onEmptyDetect() {
        if (isProcessing.get()) {
            trackingData[currentTimestamp] = emptyList()
            Log.d(TAG, "Timestamp $currentTimestamp: No detections")
        }
    }

    /**
     * Status class to represent the current state of video processing
     */
    sealed class ProcessingStatus {
        data object STARTING : ProcessingStatus()
        data object PROCESSING : ProcessingStatus()
        data object COMPLETED : ProcessingStatus()
        data object CANCELLED : ProcessingStatus()
        data class ERROR(val message: String) : ProcessingStatus()
    }

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
    }
}