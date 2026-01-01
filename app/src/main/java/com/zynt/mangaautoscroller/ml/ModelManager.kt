package com.zynt.mangaautoscroller.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ModelManager - Manages the lifecycle and caching of ML models.
 *
 * Handles:
 * - Lazy model loading (load on first use)
 * - Model caching across service restarts
 * - Background initialization
 * - Graceful degradation when model is unavailable
 * - Memory management
 */
class ModelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        @Volatile
        private var instance: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Bubble detector instance
    private var bubbleDetector: BubbleDetector? = null
    private val isInitializing = AtomicBoolean(false)
    private val initializationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Configuration
    private var currentConfig: BubbleDetectorConfig = BubbleDetectorConfig.DEFAULT

    // Listeners for initialization status
    private val statusListeners = mutableListOf<(ModelStatus) -> Unit>()

    /**
     * Current model status
     */
    val modelStatus: ModelStatus
        get() = bubbleDetector?.modelStatus ?: ModelStatus.NOT_LOADED

    /**
     * Check if the model is ready for inference
     */
    val isReady: Boolean
        get() = modelStatus == ModelStatus.READY

    /**
     * Check if the model file exists in assets
     */
    val isModelAvailable: Boolean
        get() = bubbleDetector?.isModelAvailable() ?: checkModelExists()

    /**
     * Initialize the model in the background.
     * Safe to call multiple times - will only initialize once.
     *
     * @param config Optional configuration for the detector
     * @param onComplete Callback when initialization completes
     */
    fun initializeAsync(
        config: BubbleDetectorConfig = BubbleDetectorConfig.DEFAULT,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (isInitializing.get() || isReady) {
            onComplete?.invoke(isReady)
            return
        }

        if (!isInitializing.compareAndSet(false, true)) {
            return
        }

        currentConfig = config
        Log.d(TAG, "Starting background model initialization...")

        initializationScope.launch {
            try {
                val success = initializeSync(config)

                withContext(Dispatchers.Main) {
                    notifyStatusListeners(modelStatus)
                    onComplete?.invoke(success)
                }
            } finally {
                isInitializing.set(false)
            }
        }
    }

    /**
     * Initialize the model synchronously.
     * Call from a background thread.
     */
    suspend fun initializeSync(config: BubbleDetectorConfig = BubbleDetectorConfig.DEFAULT): Boolean {
        currentConfig = config

        // Create detector if needed
        if (bubbleDetector == null) {
            bubbleDetector = BubbleDetector(context, config)
        }

        return bubbleDetector?.initialize() ?: false
    }

    /**
     * Run bubble detection on a bitmap.
     * Will attempt to initialize model if not already done.
     *
     * @param bitmap Input image
     * @param fallbackToEmpty If true, returns empty result on error instead of throwing
     * @return Detection result
     */
    suspend fun detectBubbles(
        bitmap: Bitmap,
        fallbackToEmpty: Boolean = true
    ): BubbleDetectionResult {
        // Auto-initialize if not ready
        if (!isReady) {
            val initialized = initializeSync(currentConfig)
            if (!initialized) {
                Log.w(TAG, "Model not available, returning empty result")
                return if (fallbackToEmpty) {
                    BubbleDetectionResult.empty(bitmap.width, bitmap.height)
                } else {
                    throw IllegalStateException("Model not available")
                }
            }
        }

        return try {
            bubbleDetector?.detect(bitmap)
                ?: BubbleDetectionResult.empty(bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            if (fallbackToEmpty) {
                BubbleDetectionResult.empty(bitmap.width, bitmap.height)
            } else {
                throw e
            }
        }
    }

    /**
     * Quick text density calculation from bitmap.
     * Returns a value from 0.0 (no text) to 1.0 (heavy text).
     *
     * This is the main method to use for adaptive scrolling.
     */
    suspend fun calculateTextDensity(bitmap: Bitmap): Float {
        val result = detectBubbles(bitmap, fallbackToEmpty = true)
        return result.textDensityScore
    }

    /**
     * Add a listener for model status changes
     */
    fun addStatusListener(listener: (ModelStatus) -> Unit) {
        statusListeners.add(listener)
        // Immediately notify of current status
        listener(modelStatus)
    }

    /**
     * Remove a status listener
     */
    fun removeStatusListener(listener: (ModelStatus) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun notifyStatusListeners(status: ModelStatus) {
        statusListeners.forEach { it(status) }
    }

    /**
     * Get detector statistics
     */
    fun getStats(): DetectorStats? {
        return bubbleDetector?.getStats()
    }

    /**
     * Check if model file exists without creating detector
     */
    private fun checkModelExists(): Boolean {
        return try {
            val assets = context.assets
            assets.open("models/comictextdetector.pt.onnx").use { true }
        } catch (e: Exception) {
            try {
                val assets = context.assets
                assets.open("models/comictextdetector_quantized.onnx").use { true }
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Preload model for faster first inference.
     * Call this when app starts or service begins.
     */
    fun preloadModel() {
        if (!isReady && !isInitializing.get()) {
            initializeAsync()
        }
    }

    /**
     * Release all resources.
     * Call when service is destroyed.
     */
    fun release() {
        Log.d(TAG, "Releasing ModelManager resources")
        initializationScope.cancel()
        bubbleDetector?.release()
        bubbleDetector = null
        statusListeners.clear()
    }

    /**
     * Force reload the model.
     * Useful after updating model file.
     */
    suspend fun reloadModel(config: BubbleDetectorConfig = currentConfig): Boolean {
        bubbleDetector?.release()
        bubbleDetector = BubbleDetector(context, config)
        return bubbleDetector?.initialize() ?: false
    }
}

/**
 * Extension function for easy access from services
 */
fun Context.getModelManager(): ModelManager = ModelManager.getInstance(this)
