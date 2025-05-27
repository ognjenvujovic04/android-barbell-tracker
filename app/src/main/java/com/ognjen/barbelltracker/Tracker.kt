package com.ognjen.barbelltracker

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import SORT.HungarianAlgorithm
import SORT.KalmanTracker
import kotlin.collections.ArrayList

class Tracker(
    context: Context,
    modelPath: String,
    private val trackerListener: TrackerListener,
) {

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0
    private var reusableBitmap: Bitmap? = null

    // SORT tracking variables
    private var trackers = ArrayList<KalmanTracker>()
    private val maxAge = 30 // Maximum number of frames to keep a track without detection
    private val minHits = 3 // Minimum number of hits needed to display a track
    private val iouThreshold = 0.3 // IOU threshold for matching
    private var frameCount = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        Log.d(TAG, "Initializing Tracker")
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply{
            if(compatList.isDelegateSupportedOnThisDevice){
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
                Log.d(TAG, "GPU delegate added for acceleration")
            } else {
                this.setNumThreads(4)
                Log.d(TAG, "Using 4 CPU threads for inference")
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
        Log.d(TAG, "Model loaded successfully")

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }
        Log.d(TAG, "Model input shape: width=$tensorWidth, height=$tensorHeight")
        Log.d(TAG, "Model output shape: ${outputShape?.joinToString(", ")}")

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }

        labels.add("barbell")

        // Initialize SORT tracking
        KalmanTracker.setKf_count(0)
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numElements == 0 || numChannel == 0) {
            Log.w(TAG, "Model dimensions not initialized, skipping detection")
            return
        }

        var inferenceTime = SystemClock.uptimeMillis()

        reusableBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(reusableBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(imageBuffer, output.buffer)

        // Get detected boxes from YOLO model
        val detectedBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        Log.d(TAG, "Frame: $frameCount, detected boxes: ${detectedBoxes?.size}, inference time: $inferenceTime ms")

        if (detectedBoxes.isNullOrEmpty()) {
            // Update trackers with no detections
            frameCount++
            updateTrackersWithNoDetections(frame.width, frame.height)
            return
        }

        // Apply SORT tracking
        val trackedBoxes = updateTrackers(detectedBoxes, frame.width, frame.height)

        trackerListener.onDetect(trackedBoxes, inferenceTime)
    }

    private fun updateTrackersWithNoDetections(frameWidth: Int, frameHeight: Int): List<BoundingBox>? {
        // Predict new locations of existing trackers
        val predictedBoxes = ArrayList<BoundingBox>()
        val currTrackers = ArrayList<KalmanTracker>()

        // First update all trackers and get predictions
        for (tracker in trackers) {
            val box = tracker.predict()
            if (box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0) {
                // Keep tracker active
                currTrackers.add(tracker)

                // Only output tracks that have been confirmed (reached minHits)
                if ((tracker.m_time_since_update < maxAge) && (tracker.m_hit_streak >= minHits || frameCount <= minHits)) {
                    val w = frameWidth.toFloat()
                    val h = frameHeight.toFloat()

                    // Convert the box to normalized coordinates
                    val x1 = box.x.toFloat() / w
                    val y1 = box.y.toFloat() / h
                    val x2 = (box.x + box.width).toFloat() / w
                    val y2 = (box.y + box.height).toFloat() / h
                    val cx = (x1 + x2) / 2
                    val cy = (y1 + y2) / 2

                    // Create a bounding box with track ID
                    val boundingBox = BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = (x2 - x1), h = (y2 - y1),
                        cnf = 0.0f, cls = 0, clsName = labels[0],
                        id = tracker.m_id
                    )
                    predictedBoxes.add(boundingBox)
                }
            }
        }

        // Update trackers list with active trackers
        trackers = currTrackers

        return if (predictedBoxes.isEmpty()) null else predictedBoxes
    }

    private fun updateTrackers(detectedBoxes: List<BoundingBox>, frameWidth: Int, frameHeight: Int): List<BoundingBox> {
        frameCount++

        // Convert normalized coordinates to absolute for Kalman trackers
        val detectionRects = detectedBoxes.map { bbox ->
            Rect(
                (bbox.x1 * frameWidth).toInt(),
                (bbox.y1 * frameHeight).toInt(),
                (bbox.w * frameWidth).toInt(),
                (bbox.h * frameHeight).toInt()
            )
        }

        // Get predictions from existing trackers
        val predictedBoxes = ArrayList<Rect>()
        val currTrackers = ArrayList<KalmanTracker>()

        for (tracker in trackers) {
            val box = tracker.predict()
            if (box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0) {
                predictedBoxes.add(box)
                currTrackers.add(tracker)
            }
        }

        trackers = currTrackers

        // Associate detections to tracked objects
        val iouMatrix = Mat(predictedBoxes.size, detectionRects.size, CvType.CV_64F)

        // Build cost matrix for Hungarian algorithm
        for (i in predictedBoxes.indices) {
            for (j in detectionRects.indices) {
                val iou = calculateIoU(predictedBoxes[i], detectionRects[j])
                iouMatrix.put(i, j, 1.0 - iou)  // Cost is 1-IOU (lower is better)
            }
        }

        // Apply Hungarian algorithm for assignment
        val assignment = ArrayList<Int>()
        val hungarianAlgo = HungarianAlgorithm()
        hungarianAlgo.Solve(iouMatrix, assignment)

        // Unmatched detections and trackers
        val unmatchedDetections = mutableSetOf<Int>()
        val unmatchedTrackers = mutableSetOf<Int>()
        val matchedIndices = arrayListOf<Pair<Int, Int>>()

        // Find unmatched detections
        for (i in detectionRects.indices) {
            unmatchedDetections.add(i)
        }

        // Find matches and unmatched trackers
        for (i in assignment.indices) {
            if (assignment[i] >= 0) {
                val iou = 1.0 - iouMatrix.get(i, assignment[i])[0]
                if (iou >= iouThreshold) {
                    matchedIndices.add(Pair(i, assignment[i]))
                    unmatchedDetections.remove(assignment[i])
                } else {
                    unmatchedTrackers.add(i)
                }
            } else {
                unmatchedTrackers.add(i)
            }
        }

        // Update matched trackers
        for ((trkIdx, detIdx) in matchedIndices) {
            trackers[trkIdx].update(detectionRects[detIdx])
        }

        // Create new trackers for unmatched detections
        for (detIdx in unmatchedDetections) {
            val tracker = KalmanTracker(detectionRects[detIdx])
            trackers.add(tracker)
        }

        // Create result list
        val resultBoxes = ArrayList<BoundingBox>()

        // Get boxes from active trackers
        for (i in trackers.indices) {
            val tracker = trackers[i]
            if ((tracker.m_time_since_update < maxAge) && (tracker.m_hit_streak >= minHits || frameCount <= minHits)) {
                val box = tracker.get_state()

                // Convert back to normalized coordinates
                val x1 = box.x.toFloat() / frameWidth
                val y1 = box.y.toFloat() / frameHeight
                val x2 = (box.x + box.width).toFloat() / frameWidth
                val y2 = (box.y + box.height).toFloat() / frameHeight

                val boundingBox = BoundingBox(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    cx = (x1 + x2) / 2, cy = (y1 + y2) / 2,
                    w = x2 - x1, h = y2 - y1,
                    cnf = detectedBoxes.getOrNull(0)?.cnf ?: 0.8f,  // Use detection confidence if available
                    cls = 0, clsName = labels[0],
                    id = tracker.m_id  // Use tracker ID as object ID
                )
                resultBoxes.add(boundingBox)
            }
        }

        // Clean up dead trackers
        trackers.removeAll { it.m_time_since_update > maxAge }

        return resultBoxes
    }

    private fun bestBox(array: FloatArray) : List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels[maxIdx]
                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>) : MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    private fun calculateIoU(box1: Rect, box2: Rect): Double {
        val x1 = maxOf(box1.x, box2.x)
        val y1 = maxOf(box1.y, box2.y)
        val x2 = minOf(box1.x + box1.width, box2.x + box2.width)
        val y2 = minOf(box1.y + box1.height, box2.y + box2.height)

        if (x2 < x1 || y2 < y1) return 0.0

        val intersectionArea = (x2 - x1) * (y2 - y1)
        val box1Area = box1.width * box1.height
        val box2Area = box2.width * box2.height

        return intersectionArea.toDouble() / (box1Area + box2Area - intersectionArea)
    }

    fun close() {
        try {
            Log.d(TAG, "Closing interpreter")
            interpreter.close()
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error closing model: ${e.message}", e)
        }
    }

    interface TrackerListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.8F
        private const val IOU_THRESHOLD = 0.5F
    }
}