package com.ognjen.barbelltracker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ognjen.barbelltracker.FastVideoFrameExtractor.Frame
import com.ognjen.barbelltracker.FastVideoFrameExtractor.FrameExtractor
import com.ognjen.barbelltracker.FastVideoFrameExtractor.IVideoFrameExtractor
import com.ognjen.barbelltracker.FastVideoFrameExtractor.Utils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A class that processes videos to track barbell paths using the BarbellDetector class.
 * It extracts frames from a video using hardware-accelerated FrameExtractor, processes them
 * with object detection, and stores tracking data in a timestamp-based map for efficient playback.
 */
class VideoProcessor(
    private val context: Context,
    private val modelPath: String
) : Tracker.TrackerListener, IVideoFrameExtractor {

    // Tracking data - maps time (milliseconds) to detected bounding boxes
    private val trackingData = mutableMapOf<Long, List<BoundingBox>>()

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private var videoDuration: Long = 0
    private var processedFrames: Int = 0

    private val executorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _processingStatusLiveData = MutableLiveData<ProcessingStatus>()
    val processingStatusLiveData: LiveData<ProcessingStatus> = _processingStatusLiveData

    // Tracker instance
    private var tracker: Tracker? = null

    // FrameExtractor instance
    private var frameExtractor: FrameExtractor? = null

    /**
     * Process a video file and extract barbell tracking data using FrameExtractor
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
            tracker = tracker ?: Tracker(context, modelPath, this)

            // Get video file path from URI
            val videoPath = Utils.getPath(context, videoUri)
            if (videoPath == null) {
                _processingStatusLiveData.postValue(ProcessingStatus.ERROR("Cannot get video file path"))
                return emptyMap()
            }

            // Get video metadata using MediaMetadataRetriever (only for duration info)
            val retriever = MediaMetadataRetriever().apply {
                setDataSource(context, videoUri)
            }

            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDuration = durationString?.toLong() ?: 0L
            Log.d(TAG, "Video duration: $videoDuration ms")

            retriever.release()

            if (videoDuration <= 0) {
                _processingStatusLiveData.postValue(ProcessingStatus.ERROR("Invalid video duration"))
                return emptyMap()
            }

            // Create FrameExtractor instance
            frameExtractor = FrameExtractor(this)

            // Start processing in background
            isProcessing.set(true)
            _processingStatusLiveData.postValue(ProcessingStatus.PROCESSING)

            executorService.execute {
                val startTime = SystemClock.uptimeMillis()

                try {
                    // Start frame extraction - this will call our callbacks
                    frameExtractor?.extractFrames(videoPath)
                } catch (e: Exception) {
                    Log.e(ERRORTAG, "Frame extraction error: ${e.message}", e)
                    mainHandler.post {
                        _processingStatusLiveData.value = ProcessingStatus.ERROR(e.message ?: "Frame extraction failed")
                    }
                } finally {
                    val totalTime = SystemClock.uptimeMillis() - startTime
                    Log.d(TAG, "Total processing time: $totalTime ms")
                }
            }

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Init error: ${e.message}", e)
            _processingStatusLiveData.postValue(ProcessingStatus.ERROR(e.message ?: "Unknown error"))
            releaseResources()
        }

        return trackingData
    }

    /**
     * Callback from FrameExtractor when a frame is extracted
     */
    override fun onCurrentFrameExtracted(currentFrame: Frame, presentationTimeUs: Long) {
        if (!isProcessing.get()) return

        try {
            val imageBitmap = Utils.fromBufferToBitmap(currentFrame.byteBuffer, currentFrame.width, currentFrame.height)


            // Process the frame with the tracker
            tracker?.detect(imageBitmap)

            processedFrames++

            // Update progress if needed
            if (processedFrames % 30 == 0) { // Update every 30 frames
                Log.d(TAG, "Processed $processedFrames frames")
            }

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error processing frame: ${e.message}", e)
        }
    }

    /**
     * Callback from FrameExtractor when all frames are processed
     */
    override fun onAllFrameExtracted(processedFrameCount: Int, processedTimeMs: Long) {
        Log.d(TAG, "Frame extraction completed. Processed $processedFrameCount frames in $processedTimeMs ms")

        mainHandler.post {
            if (isProcessing.get()) {
                _processingStatusLiveData.value = ProcessingStatus.COMPLETED
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
            frameExtractor?.isTerminated = true // Stop the frame extractor
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
        frameExtractor = null
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
    override fun onDetect(boundingBoxes: List<BoundingBox>) {
        if (isProcessing.get()) {
            // Calculate current timestamp based on processed frames
            // This is approximate - FrameExtractor provides exact timing in onCurrentFrameExtracted
            val currentTimestamp = (processedFrames * 1000L) / 30L // Assuming ~30fps
            trackingData[currentTimestamp] = boundingBoxes
        }
    }

    /**
     * Callback from BarbellDetector when no objects are detected
     */
    override fun onEmptyDetect() {
        if (isProcessing.get()) {
            val currentTimestamp = (processedFrames * 1000L) / 30L
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