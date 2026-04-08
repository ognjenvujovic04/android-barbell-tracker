package com.ognjen.barbelltracker.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.Frame
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.FrameExtractor
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.IVideoFrameExtractor
import com.ognjen.barbelltracker.domain.FastVideoFrameExtractor.Utils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.min

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
    private val velocityByTimestampMs = mutableMapOf<Long, BarVelocitySample>()
    private val firstTrackingBoxes = mutableListOf<BoundingBox>()
    private var tracker: Tracker? = null
    private var frameExtractor: FrameExtractor? = null

    var selectedBarbellId: Int? = null

    /**
     * Physical bar shaft diameter in centimetres, used with the smaller bbox side in pixels
     * to obtain cm/pixel (same idea for React Native: expose this as a numeric prop).
     * Default matches product request; Olympic bar shafts are typically ~2.8 cm if you need that scale.
     */
    var barDiameterCm: Float = DEFAULT_BAR_DIAMETER_CM

    private var currentPresentationTimeUs: Long = 0L
    private var currentFrameWidth: Int = 0
    private var currentFrameHeight: Int = 0

    private var lastTimeUs: Long? = null
    private var lastSmoothedCenterCmX: Float? = null
    private var lastSmoothedCenterCmY: Float? = null

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private var processedFrames = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reusableBitmap: Bitmap? = null

    fun processVideoAsync(
        uri: Uri,
        onProgress: (Int) -> Unit,
        onComplete: (Map<Long, List<BoundingBox>>, Map<Long, BarVelocitySample>) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val result = processVideo(uri, onProgress)
                mainHandler.post { onComplete(result, velocityByTimestampMs.toMap()) }
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
        velocityByTimestampMs.clear()
        lastTimeUs = null
        lastSmoothedCenterCmX = null
        lastSmoothedCenterCmY = null
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

        val processingStartMs = SystemClock.elapsedRealtime()
        // Frame extraction starts (callbacks fill trackingData)
        frameExtractor!!.extractFrames(videoPath)
        val processingElapsedMs = SystemClock.elapsedRealtime() - processingStartMs

        Log.i(
            BARBELL_TRACKER_LOG_TAG,
            "Video processing completed in ${processingElapsedMs} ms"
        )
        mainHandler.post {
            Toast.makeText(
                context,
                "Processing took ${processingElapsedMs} ms",
                Toast.LENGTH_SHORT
            ).show()
        }

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
            currentPresentationTimeUs = presentationTimeUs
            currentFrameWidth = currentFrame.width
            currentFrameHeight = currentFrame.height

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
        val timestampMs = currentPresentationTimeUs / 1000L
        trackingData[timestampMs] = boundingBoxes
        appendVelocityIfPossible(boundingBoxes, timestampMs)
    }

    override fun onEmptyDetect() {
        val timestampMs = currentPresentationTimeUs / 1000L
        trackingData[timestampMs] = emptyList()
        resetVelocityChain()
    }

    private fun pickTrackedBox(boxes: List<BoundingBox>): BoundingBox? {
        if (boxes.isEmpty()) return null
        val id = selectedBarbellId ?: return boxes.first()
        return boxes.firstOrNull { it.id == id } ?: boxes.first()
    }

    private fun resetVelocityChain() {
        lastTimeUs = null
        lastSmoothedCenterCmX = null
        lastSmoothedCenterCmY = null
    }

    private fun appendVelocityIfPossible(boxes: List<BoundingBox>, timestampMs: Long) {
        val box = pickTrackedBox(boxes) ?: run {
            resetVelocityChain()
            return
        }
        val fw = currentFrameWidth.toFloat()
        val fh = currentFrameHeight.toFloat()
        if (fw <= 0f || fh <= 0f || barDiameterCm <= 0f) return

        val wPx = box.w * fw
        val hPx = box.h * fh
        val barPixelSpan = min(wPx, hPx)
        if (barPixelSpan < 1f) return

        val cmPerPixel = barDiameterCm / barPixelSpan
        val rawCxCm = box.cx * fw * cmPerPixel
        val rawCyCm = box.cy * fh * cmPerPixel

        val tUs = currentPresentationTimeUs
        val prevT0 = lastTimeUs
        if (prevT0 != null && (tUs - prevT0) > MAX_GAP_US) {
            resetVelocityChain()
        }

        val prevT = lastTimeUs
        val prevSmoothedX = lastSmoothedCenterCmX
        val prevSmoothedY = lastSmoothedCenterCmY

        // EMA on position before differencing: reduces velocity noise from bbox jitter.
        val smoothedX: Float
        val smoothedY: Float
        if (prevSmoothedX == null || prevSmoothedY == null) {
            smoothedX = rawCxCm
            smoothedY = rawCyCm
        } else {
            smoothedX = POSITION_EMA_ALPHA * rawCxCm + (1f - POSITION_EMA_ALPHA) * prevSmoothedX
            smoothedY = POSITION_EMA_ALPHA * rawCyCm + (1f - POSITION_EMA_ALPHA) * prevSmoothedY
        }

        if (prevT != null && prevSmoothedX != null && prevSmoothedY != null) {
            val dtUs = tUs - prevT
            if (dtUs > 0L) {
                val dtSec = dtUs / 1_000_000f
                val dxCm = smoothedX - prevSmoothedX
                val dyCm = smoothedY - prevSmoothedY
                val vx = dxCm / dtSec
                val vy = dyCm / dtSec
                val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
                val segment = hypot(dxCm.toDouble(), dyCm.toDouble()).toFloat()
                velocityByTimestampMs[timestampMs] = BarVelocitySample(
                    timestampMs = timestampMs,
                    dtSeconds = dtSec,
                    vxCmPerS = vx,
                    vyCmPerS = vy,
                    speedCmPerS = speed,
                    segmentPathCm = segment,
                )
            }
        }

        lastTimeUs = tUs
        lastSmoothedCenterCmX = smoothedX
        lastSmoothedCenterCmY = smoothedY
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
        const val BARBELL_TRACKER_LOG_TAG = "BarbelltrackerLog"
        const val DEFAULT_BAR_DIAMETER_CM = 45f
        /** Break velocity segments after this many µs without a sample (avoids one huge Δt spike). */
        private const val MAX_GAP_US = 1_000_000L

        /**
         * Weight of the new detection vs previous smoothed centre (0–1). Higher = follow the model more,
         * lower = smoother curve (more lag). Typical range 0.25–0.55.
         */
        private const val POSITION_EMA_ALPHA = 0.4f
    }
}
