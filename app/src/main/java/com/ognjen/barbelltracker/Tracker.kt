package com.ognjen.barbelltracker

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Tracker(
    context: Context,
    modelPath: String,
    private val trackerListener: TrackerListener,
) {

    private var interpreter: Interpreter? = null
    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .build()

    init {
        try {
            Log.d(TAG, "Initializing Tracker")
            val options = Interpreter.Options().apply {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
                    Log.d(TAG, "GPU delegate added for acceleration")
                } else {
                    numThreads = 4
                    Log.d(TAG, "Using 4 CPU threads for inference")
                }
            }

            val model = FileUtil.loadMappedFile(context, modelPath)
            interpreter = Interpreter(model, options)
            Log.d(TAG, "Model loaded successfully")

            // Log input tensor shape
            interpreter?.getInputTensor(0)?.shape()?.let { shape ->
                tensorWidth = shape[1]
                tensorHeight = shape[2]
                Log.d(TAG, "Model input shape: width=$tensorWidth, height=$tensorHeight")
            }

            // Log output tensor shape
            interpreter?.getOutputTensor(0)?.shape()?.let { shape ->
                Log.d(TAG, "Model output shape: ${shape.joinToString(", ")}")
                numElements = shape[2] // Adjust this based on the actual output shape
            }
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error initializing model: ${e.message}", e)
        }
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numElements == 0) {
            Log.w(TAG, "Model dimensions not initialized, skipping detection")
            return
        }

        try {
            val startTime = SystemClock.uptimeMillis()
            Log.d(TAG, "Starting detection")

            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(DataType.FLOAT32).apply { load(resizedBitmap) }
            val processedImage = imageProcessor.process(tensorImage)
            val imageBuffer = processedImage.buffer

            // Create output buffer based on the model's output tensor shape
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val output = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

            interpreter?.run(imageBuffer, output.buffer)
            Log.d(TAG, "Inference completed")

            val boundingBox = extractBoundingBox(output.floatArray)
            val inferenceTime = SystemClock.uptimeMillis() - startTime
            Log.d(TAG, "Inference time: $inferenceTime ms")

            if (boundingBox != null) {
                Log.d(TAG, "Bounding box detected: $boundingBox")
                trackerListener.onTrack(boundingBox, inferenceTime)
            } else {
                Log.d(TAG, "No bounding box detected")
                trackerListener.onNoTrack()
            }
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error during detection: ${e.message}", e)
        }
    }

    private fun extractBoundingBox(array: FloatArray): BoundingBox? {
        if (array[4] < CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Detection confidence ${array[4]} below threshold, ignoring")
            return null
        }

        val cx = array[0]
        val cy = array[1]
        val w = array[2]
        val h = array[3]
        Log.d(TAG, "Extracted bounding box: cx=$cx, cy=$cy, w=$w, h=$h")

        return BoundingBox(
            x1 = cx - w / 2F, y1 = cy - h / 2F,
            x2 = cx + w / 2F, y2 = cy + h / 2F,
            cx = cx, cy = cy, w = w, h = h,
            cnf = array[4]
        )
    }

    fun close() {
        try {
            Log.d(TAG, "Closing interpreter")
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(ERRORTAG, "Error closing model: ${e.message}", e)
        }
    }

    interface TrackerListener {
        fun onNoTrack()
        fun onTrack(boundingBox: BoundingBox, inferenceTime: Long)
    }

    companion object {
        private const val TAG = "BarbelltrackerLog"
        private const val ERRORTAG = "BarbelltrackerError"
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private const val CONFIDENCE_THRESHOLD = 0.03F
    }
}
