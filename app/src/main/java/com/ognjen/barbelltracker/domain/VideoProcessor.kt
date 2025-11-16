package com.ognjen.barbelltracker.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.Frame
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.FrameExtractor
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.IVideoFrameExtractor
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.Utils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated VideoProcessor class.
 *
 * Works standalone in Kotlin and is ready for bridging to React Native.
 */
class VideoProcessor(
    private val context: Context,
    private val modelPath: String
) : Tracker.TrackerListener, IVideoFrameExtractor {

    // =========================================================
    // Internal State
    // =========================================================
    private val trackingData = mutableMapOf<Long, List<BoundingBox>>()
    private val firstTrackingBoxes = mutableListOf<BoundingBox>()
    private var tracker: Tracker? = null
    private var frameExtractor: FrameExtractor? = null

    var selectedBarbellId: Int? = null

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private var processedFrames = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reusableBitmap: Bitmap? = null

    fun processVideoAsync(
        uri: Uri,
        onProgress: (Int) -> Unit,
        onComplete: (Map<Long, List<BoundingBox>>) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val result = processVideo(uri, onProgress)
                mainHandler.post { onComplete(result) }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun processVideo(
        videoUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): Map<Long, List<BoundingBox>> {

        if (isProcessing.get()) {
            Log.w(TAG, "Already processing video")
            return emptyMap()
        }

        trackingData.clear()
        processedFrames = 0

        val videoPath = Utils.getPath(context, videoUri)
            ?: throw Exception("Cannot resolve video path")

        // Get video metadata
        val retriever = MediaMetadataRetriever().apply {
            setDataSource(context, videoUri)
        }
        val videoDuration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLong() ?: 0L
        retriever.release()

        tracker = Tracker(context, modelPath, this)
        frameExtractor = FrameExtractor(this)

        isProcessing.set(true)

        // Frame extraction starts (callbacks fill trackingData)
        frameExtractor!!.extractFrames(videoPath)

        return trackingData
    }

    fun firstFrame(videoUri: Uri): Bitmap? {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)

            // Extract first frame (time = 0)
            bitmap = retriever.getFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )

            if (bitmap == null) {
                Log.e(ERRORTAG, "Failed to retrieve first frame from video")
                return null
            }

            // Use a temporary tracker so we don't conflict with main processing tracker
            val tempTracker = Tracker(context, modelPath, this)

            // Make a safe copy for detection
            val processedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // Run detection telling tracker it is first frame
            tempTracker.detect(processedBitmap, true)

            // Close temp tracker
            tempTracker.close()

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error getting first frame: ${e.message}", e)
        } finally {
            retriever.release()
        }

        return bitmap
    }

    fun getFirstTrackingBoxes(): List<BoundingBox> {
        return firstTrackingBoxes.toList()
    }

    override fun onCurrentFrameExtracted(currentFrame: Frame, presentationTimeUs: Long) {
        if (!isProcessing.get()) return

        try {
            reusableBitmap = Utils.fromBufferToBitmap(
                currentFrame.byteBuffer,
                currentFrame.width,
                currentFrame.height
            )
            tracker?.detect(reusableBitmap!!)
            processedFrames++

        } catch (e: Exception) {
            Log.e(ERRORTAG, "Frame error: ${e.message}", e)
        }
    }

    override fun onAllFrameExtracted(processedFrameCount: Int, processedTimeMs: Long) {
        isProcessing.set(false)
        frameExtractor = null
        tracker?.close()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>) {
        if (!isProcessing.get()) return
        val timestamp = (processedFrames * 1000L) / 30L
        trackingData[timestamp] = boundingBoxes
    }

    override fun onEmptyDetect() {
        val timestamp = (processedFrames * 1000L) / 30L
        trackingData[timestamp] = emptyList()
    }

    override fun onFirstDetect(boundingBoxes: List<BoundingBox>) {
        firstTrackingBoxes.clear()
        firstTrackingBoxes.addAll(boundingBoxes)
    }

    fun stopProcessing() {
        if (isProcessing.get()) {
            isProcessing.set(false)
            frameExtractor?.isTerminated = true
        }
    }

    fun close() {
        stopProcessing()
        tracker?.close()
        executor.shutdown()
    }

    companion object {
        const val TAG = "VideoProcessor"
        const val ERRORTAG = "VideoProcessorError"
    }
}
