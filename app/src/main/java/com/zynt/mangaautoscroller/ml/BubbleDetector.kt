package com.zynt.mangaautoscroller.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * BubbleDetector - ONNX-based manga text bubble detection.
 *
 * Uses the Comic Text Detector model to detect speech bubbles and text regions
 * in manga/manhwa/manhua images. The model runs entirely offline on-device.
 *
 * Model: comictextdetector.pt.onnx from manga-image-translator project
 * Input: 1024x1024 RGB image (normalized to 0-1)
 * Output: Bounding boxes with confidence scores
 */
class BubbleDetector(
    private val context: Context,
    private val config: BubbleDetectorConfig = BubbleDetectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "BubbleDetector"
        private const val MODEL_FILENAME = "comictextdetector.pt.onnx"
        private const val QUANTIZED_MODEL_FILENAME = "comictextdetector_quantized.onnx"

        // Model input/output names (may need adjustment based on actual model)
        private const val INPUT_NAME = "images"
        private const val OUTPUT_NAME = "output"

        // Singleton instance for efficiency
        @Volatile
        private var instance: BubbleDetector? = null

        fun getInstance(context: Context, config: BubbleDetectorConfig = BubbleDetectorConfig.DEFAULT): BubbleDetector {
            return instance ?: synchronized(this) {
                instance ?: BubbleDetector(context.applicationContext, config).also { instance = it }
            }
        }
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var _modelStatus: ModelStatus = ModelStatus.NOT_LOADED
    val modelStatus: ModelStatus get() = _modelStatus

    private var lastInferenceTimeMs: Long = 0
    private var totalInferences: Int = 0

    /**
     * Initialize the ONNX Runtime and load the model.
     * Call this before running detection.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_modelStatus == ModelStatus.READY) {
            Log.d(TAG, "Model already loaded")
            return@withContext true
        }

        _modelStatus = ModelStatus.LOADING
        Log.d(TAG, "Initializing BubbleDetector...")

        try {
            // Check if model file exists
            val modelPath = getModelPath()
            if (modelPath == null) {
                Log.e(TAG, "Model file not found in assets")
                _modelStatus = ModelStatus.NOT_AVAILABLE
                return@withContext false
            }

            // Create ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Configure session options - OPTIMIZED for mobile CPU
            // Note: NNAPI on most devices uses "nnapi-reference" (CPU fallback)
            // which is SLOWER than direct CPU inference, so we disable it
            val sessionOptions = OrtSession.SessionOptions().apply {
                // Set optimization level
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                // Use all available CPU cores for maximum performance
                // NNAPI is disabled because "nnapi-reference" is slower than direct CPU
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
                setInterOpNumThreads(2)
                
                Log.d(TAG, "Using optimized CPU inference with ${Runtime.getRuntime().availableProcessors()} threads")
            }

            // Load model from assets
            val modelBytes = context.assets.open(modelPath).use { it.readBytes() }
            ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)

            _modelStatus = ModelStatus.READY
            Log.d(TAG, "✅ Model loaded successfully: $modelPath")
            logModelInfo()

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load model", e)
            _modelStatus = ModelStatus.ERROR
            false
        }
    }

    /**
     * Get the path to the model file in assets.
     * Uses the full float32 model (quantized is slower without INT8 hardware support).
     */
    private fun getModelPath(): String? {
        val assetManager = context.assets

        // Use full float32 model - quantized is actually slower on most devices
        // because NNAPI's INT8 fallback has overhead without dedicated NPU
        try {
            assetManager.open("models/$MODEL_FILENAME").close()
            Log.d(TAG, "Using float32 model (faster than quantized on CPU)")
            return "models/$MODEL_FILENAME"
        } catch (e: Exception) {
            // Full model not found, try quantized as fallback
        }

        try {
            assetManager.open("models/$QUANTIZED_MODEL_FILENAME").close()
            Log.d(TAG, "Using quantized model (fallback)")
            return "models/$QUANTIZED_MODEL_FILENAME"
        } catch (e: Exception) {
            // Quantized model not found
        }

        return null
    }

    /**
     * Log model information for debugging
     */
    private fun logModelInfo() {
        ortSession?.let { session ->
            Log.d(TAG, "Model inputs: ${session.inputNames}")
            Log.d(TAG, "Model outputs: ${session.outputNames}")

            session.inputInfo.forEach { (name, info) ->
                Log.d(TAG, "Input '$name': ${info.info}")
            }
            session.outputInfo.forEach { (name, info) ->
                Log.d(TAG, "Output '$name': ${info.info}")
            }
        }
    }

    /**
     * Run bubble detection on a bitmap.
     *
     * @param bitmap Input image (any size, will be resized)
     * @return Detection result with all detected bubbles
     */
    suspend fun detect(bitmap: Bitmap): BubbleDetectionResult = withContext(Dispatchers.Default) {
        if (_modelStatus != ModelStatus.READY) {
            Log.w(TAG, "Model not ready, status: $_modelStatus")
            return@withContext BubbleDetectionResult.empty(bitmap.width, bitmap.height)
        }

        val startTime = System.currentTimeMillis()

        try {
            // Preprocess: resize and normalize
            val inputTensor = preprocessBitmap(bitmap)

            // Run inference
            val outputs = runInference(inputTensor)

            // Postprocess: extract bounding boxes
            val detections = postprocessOutputs(outputs, bitmap.width, bitmap.height)

            val inferenceTime = System.currentTimeMillis() - startTime
            lastInferenceTimeMs = inferenceTime
            totalInferences++

            Log.d(TAG, "Detection completed: ${detections.size} bubbles in ${inferenceTime}ms")

            BubbleDetectionResult(
                detections = detections,
                inferenceTimeMs = inferenceTime,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            BubbleDetectionResult.empty(bitmap.width, bitmap.height)
        }
    }

    /**
     * Preprocess bitmap for model input.
     * Resizes to model input size and normalizes pixel values.
     */
    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        val inputSize = config.inputSize
        val env = ortEnvironment ?: throw IllegalStateException("ORT environment not initialized")

        // Resize bitmap to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Extract pixels and normalize to 0-1 range
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Create float buffer in NCHW format (batch, channels, height, width)
        // Model expects: [1, 3, 1024, 1024]
        val floatBuffer = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)

        // Convert to CHW format with normalization
        for (c in 0 until 3) { // RGB channels
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = pixels[y * inputSize + x]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f   // G
                        2 -> (pixel and 0xFF) / 255.0f           // B
                        else -> 0f
                    }
                    floatBuffer.put(value)
                }
            }
        }
        floatBuffer.rewind()

        // Clean up resized bitmap if it's different from original
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        // Create tensor
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    /**
     * Run model inference
     */
    private fun runInference(inputTensor: OnnxTensor): OrtSession.Result {
        val session = ortSession ?: throw IllegalStateException("Session not initialized")

        // Get actual input name from model
        val inputName = session.inputNames.firstOrNull() ?: INPUT_NAME

        val inputs = mapOf(inputName to inputTensor)
        return session.run(inputs)
    }

    /**
     * Postprocess model outputs to extract bounding boxes.
     * 
     * Comic Text Detector YOLO output format: [1, 64512, 7]
     * Where 7 values are: [cx, cy, w, h, obj_conf, class1_conf, class2_conf]
     * - cx, cy: center coordinates (in input_size pixels, e.g., 0-1024)
     * - w, h: width/height (in pixels)
     * - obj_conf: objectness confidence
     * - class_confs: per-class confidence scores
     */
    private fun postprocessOutputs(
        outputs: OrtSession.Result,
        originalWidth: Int,
        originalHeight: Int
    ): List<BubbleDetection> {
        val detections = mutableListOf<BubbleDetection>()

        try {
            // Get output tensor - format varies by model version
            val outputTensor = outputs.get(0) as? OnnxTensor
                ?: return emptyList()

            val outputData = outputTensor.floatBuffer
            val outputShape = outputTensor.info.shape

            Log.d(TAG, "Output shape: ${outputShape.contentToString()}")

            // Parse YOLO-style output: [1, num_anchors, 7]
            // Format: [cx, cy, w, h, obj_conf, class1_conf, class2_conf]
            when {
                outputShape.size == 3 && outputShape[2] >= 5 -> {
                    val numDetections = outputShape[1].toInt()
                    val numAttributes = outputShape[2].toInt()
                    val inputSize = config.inputSize.toFloat()
                    val threshold = config.confidenceThreshold
                    
                    // OPTIMIZATION: Convert FloatBuffer to array for MUCH faster access
                    // FloatBuffer.get(index) is very slow compared to array access
                    outputData.rewind()
                    val dataArray = FloatArray(outputData.remaining())
                    outputData.get(dataArray)
                    
                    // Track max confidence for debug logging (only on first few inferences)
                    var maxObjConf = 0f
                    var maxCombinedConf = 0f
                    val shouldLogDebug = totalInferences < 3
                    
                    // SINGLE PASS through all detections - much faster!
                    for (i in 0 until numDetections) {
                        val offset = i * numAttributes

                        // YOLO format: center_x, center_y, width, height, obj_conf, class_confs...
                        val objConf = dataArray[offset + 4]
                        
                        // Quick reject: skip if objectness is too low
                        // Most anchors have very low confidence, so this skips ~99% of iterations
                        if (objConf < 0.01f) continue
                        
                        val cx = dataArray[offset]
                        val cy = dataArray[offset + 1]
                        val w = dataArray[offset + 2]
                        val h = dataArray[offset + 3]

                        // Get class confidence
                        val class1 = if (numAttributes > 5) dataArray[offset + 5] else 1f
                        val class2 = if (numAttributes > 6) dataArray[offset + 6] else 0f
                        val maxClassConf = maxOf(class1, class2)

                        // Final confidence = objectness * class_confidence
                        val confidence = objConf * maxClassConf
                        
                        // Track max for debugging
                        if (shouldLogDebug) {
                            if (objConf > maxObjConf) maxObjConf = objConf
                            if (confidence > maxCombinedConf) maxCombinedConf = confidence
                        }

                        if (confidence >= threshold) {
                            // Convert from center format to corner format
                            // Coordinates are in input_size pixels (0-1024)
                            val x1 = (cx - w / 2) / inputSize
                            val y1 = (cy - h / 2) / inputSize
                            val x2 = (cx + w / 2) / inputSize
                            val y2 = (cy + h / 2) / inputSize

                            val box = RectF(
                                x1.coerceIn(0f, 1f),
                                y1.coerceIn(0f, 1f),
                                x2.coerceIn(0f, 1f),
                                y2.coerceIn(0f, 1f)
                            )

                            // Filter out very small or invalid boxes
                            if (box.width() > 0.01f && box.height() > 0.01f) {
                                val classId = if (class1 > class2) 0 else 1

                                detections.add(
                                    BubbleDetection(
                                        boundingBox = box,
                                        confidence = confidence,
                                        classId = classId
                                    )
                                )
                            }
                        }
                    }
                    
                    // Log debug info only for first few inferences
                    if (shouldLogDebug) {
                        Log.d(TAG, "📊 Max obj=${"%.3f".format(maxObjConf)}, combined=${"%.3f".format(maxCombinedConf)}, threshold=$threshold, raw=${detections.size}")
                    }
                }

                outputShape.size == 2 && outputShape[1] >= 5 -> {
                    // [num_detections, attributes] - less common format
                    val numDetections = outputShape[0].toInt()
                    val numAttributes = outputShape[1].toInt()
                    val inputSize = config.inputSize.toFloat()

                    for (i in 0 until minOf(numDetections, config.maxDetections)) {
                        val offset = i * numAttributes

                        val cx = outputData.get(offset)
                        val cy = outputData.get(offset + 1)
                        val w = outputData.get(offset + 2)
                        val h = outputData.get(offset + 3)
                        val confidence = outputData.get(offset + 4)

                        if (confidence >= config.confidenceThreshold) {
                            val x1 = (cx - w / 2) / inputSize
                            val y1 = (cy - h / 2) / inputSize
                            val x2 = (cx + w / 2) / inputSize
                            val y2 = (cy + h / 2) / inputSize

                            val box = RectF(
                                x1.coerceIn(0f, 1f),
                                y1.coerceIn(0f, 1f),
                                x2.coerceIn(0f, 1f),
                                y2.coerceIn(0f, 1f)
                            )

                            if (box.width() > 0.01f && box.height() > 0.01f) {
                                detections.add(
                                    BubbleDetection(
                                        boundingBox = box,
                                        confidence = confidence
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Apply Non-Maximum Suppression
            val nmsDetections = applyNMS(detections, config.iouThreshold)

            Log.d(TAG, "Postprocess: ${detections.size} raw -> ${nmsDetections.size} after NMS")
            return nmsDetections

        } catch (e: Exception) {
            Log.e(TAG, "Error in postprocessing", e)
            return emptyList()
        }
    }

    /**
     * Apply Non-Maximum Suppression to remove overlapping detections
     */
    private fun applyNMS(detections: List<BubbleDetection>, iouThreshold: Float): List<BubbleDetection> {
        if (detections.isEmpty()) return emptyList()

        // Sort by confidence (descending)
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<BubbleDetection>()
        val active = BooleanArray(sorted.size) { true }

        for (i in sorted.indices) {
            if (!active[i]) continue

            selected.add(sorted[i])

            for (j in (i + 1) until sorted.size) {
                if (!active[j]) continue

                if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                    active[j] = false
                }
            }
        }

        return selected.take(config.maxDetections)
    }

    /**
     * Calculate Intersection over Union (IoU) between two boxes
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectLeft = max(box1.left, box2.left)
        val intersectTop = max(box1.top, box2.top)
        val intersectRight = min(box1.right, box2.right)
        val intersectBottom = min(box1.bottom, box2.bottom)

        val intersectArea = max(0f, intersectRight - intersectLeft) * max(0f, intersectBottom - intersectTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectArea

        return if (unionArea > 0) intersectArea / unionArea else 0f
    }

    /**
     * Check if the model file exists in assets
     */
    fun isModelAvailable(): Boolean {
        return getModelPath() != null
    }

    /**
     * Get statistics about detector performance
     */
    fun getStats(): DetectorStats {
        return DetectorStats(
            modelStatus = _modelStatus,
            lastInferenceTimeMs = lastInferenceTimeMs,
            totalInferences = totalInferences,
            isGpuEnabled = config.useGpu
        )
    }

    /**
     * Release resources
     */
    fun release() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
            ortSession = null
            ortEnvironment = null
            _modelStatus = ModelStatus.NOT_LOADED
            Log.d(TAG, "BubbleDetector released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
}

/**
 * Statistics about detector performance
 */
data class DetectorStats(
    val modelStatus: ModelStatus,
    val lastInferenceTimeMs: Long,
    val totalInferences: Int,
    val isGpuEnabled: Boolean
)
