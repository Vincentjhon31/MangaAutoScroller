package com.zynt.mangaautoscroller.ml

import android.graphics.RectF

/**
 * Data classes for ML-based manga bubble/text detection results.
 * These represent the output from the Comic Text Detector ONNX model.
 */

/**
 * Represents a single detected text bubble/region in the manga image.
 *
 * @property boundingBox The bounding box coordinates (left, top, right, bottom) normalized to 0-1
 * @property confidence Detection confidence score from 0.0 to 1.0
 * @property classId The class ID from the model (0 = text block, 1 = text line, etc.)
 * @property className Human-readable class name
 */
data class BubbleDetection(
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int = 0,
    val className: String = "text_bubble"
) {
    /**
     * Calculate the area of the bounding box (normalized 0-1 range)
     */
    val area: Float
        get() = boundingBox.width() * boundingBox.height()

    /**
     * Get the center point of the bounding box
     */
    val centerX: Float
        get() = boundingBox.centerX()

    val centerY: Float
        get() = boundingBox.centerY()

    /**
     * Check if this bubble is in the top half of the screen
     */
    val isInTopHalf: Boolean
        get() = centerY < 0.5f

    /**
     * Check if this bubble is in the bottom half of the screen
     */
    val isInBottomHalf: Boolean
        get() = centerY >= 0.5f

    /**
     * Convert normalized coordinates to pixel coordinates
     */
    fun toPixelCoordinates(imageWidth: Int, imageHeight: Int): RectF {
        return RectF(
            boundingBox.left * imageWidth,
            boundingBox.top * imageHeight,
            boundingBox.right * imageWidth,
            boundingBox.bottom * imageHeight
        )
    }
}

/**
 * Complete result from bubble detection inference.
 *
 * @property detections List of all detected bubbles/text regions
 * @property inferenceTimeMs Time taken for model inference in milliseconds
 * @property imageWidth Original image width
 * @property imageHeight Original image height
 * @property modelName Name of the model used for detection
 */
data class BubbleDetectionResult(
    val detections: List<BubbleDetection>,
    val inferenceTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val modelName: String = "comic_text_detector"
) {
    /**
     * Total number of detected bubbles
     */
    val bubbleCount: Int
        get() = detections.size

    /**
     * Check if any text bubbles were detected
     */
    val hasDetections: Boolean
        get() = detections.isNotEmpty()

    /**
     * Calculate total coverage area of all bubbles (0.0 to 1.0)
     * This represents the percentage of the image covered by text bubbles.
     */
    val totalCoverageArea: Float
        get() = detections.sumOf { it.area.toDouble() }.toFloat().coerceIn(0f, 1f)

    /**
     * Calculate text density score based on bubble count and coverage.
     * Returns a value from 0.0 (no text) to 1.0 (heavy text density).
     *
     * This is the primary metric used for adaptive scroll speed.
     */
    val textDensityScore: Float
        get() {
            if (detections.isEmpty()) return 0f

            // Combine bubble count and area coverage for density calculation
            val countFactor = (bubbleCount / 10f).coerceIn(0f, 1f) // Normalize: 10+ bubbles = max count factor
            val areaFactor = totalCoverageArea * 2f // Area is important, scale up

            // Weighted combination: area matters more than count
            return ((countFactor * 0.3f) + (areaFactor * 0.7f)).coerceIn(0f, 1f)
        }

    /**
     * Get average confidence of all detections
     */
    val averageConfidence: Float
        get() = if (detections.isEmpty()) 0f else detections.map { it.confidence }.average().toFloat()

    /**
     * Get high-confidence detections only (confidence > 0.5)
     */
    val highConfidenceDetections: List<BubbleDetection>
        get() = detections.filter { it.confidence > 0.5f }

    /**
     * Check if this appears to be a dialogue-heavy panel
     * (multiple bubbles with good coverage)
     */
    val isDialogueHeavy: Boolean
        get() = bubbleCount >= 3 || totalCoverageArea > 0.15f

    /**
     * Check if this appears to be an action/art panel
     * (few or no bubbles)
     */
    val isActionPanel: Boolean
        get() = bubbleCount <= 1 && totalCoverageArea < 0.05f

    /**
     * Get bubbles in the visible area (useful for scroll decisions)
     * @param visibleTopRatio Top of visible area (0.0 to 1.0)
     * @param visibleBottomRatio Bottom of visible area (0.0 to 1.0)
     */
    fun getBubblesInVisibleArea(visibleTopRatio: Float = 0.3f, visibleBottomRatio: Float = 0.7f): List<BubbleDetection> {
        return detections.filter { bubble ->
            bubble.centerY >= visibleTopRatio && bubble.centerY <= visibleBottomRatio
        }
    }

    companion object {
        /**
         * Create an empty result (when model is not available or detection fails)
         */
        fun empty(imageWidth: Int = 0, imageHeight: Int = 0): BubbleDetectionResult {
            return BubbleDetectionResult(
                detections = emptyList(),
                inferenceTimeMs = 0,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }
}

/**
 * Model loading status
 */
enum class ModelStatus {
    NOT_LOADED,
    LOADING,
    READY,
    ERROR,
    NOT_AVAILABLE  // Model file doesn't exist
}

/**
 * Configuration for the bubble detector
 */
data class BubbleDetectorConfig(
    val confidenceThreshold: Float = 0.15f,   // Minimum confidence to keep detection (lowered for better recall)
    val iouThreshold: Float = 0.45f,          // IoU threshold for NMS
    val maxDetections: Int = 50,              // Maximum number of detections to return
    val inputSize: Int = 1024,                // Model input size (FIXED at 1024x1024 for comic text detector)
    val useGpu: Boolean = false               // DISABLED - NNAPI "reference" is slower than CPU
) {
    companion object {
        val DEFAULT = BubbleDetectorConfig()

        // TURBO mode - maximum speed, still accurate enough for scroll adaptation
        // Uses CPU-only with higher threshold for faster processing
        val TURBO = BubbleDetectorConfig(
            confidenceThreshold = 0.25f,  // Higher threshold = fewer detections to process
            iouThreshold = 0.5f,          // Slightly higher = faster NMS
            maxDetections = 20,           // Limit detections for faster processing
            inputSize = 1024,
            useGpu = false                // CPU is faster than NNAPI reference
        )

        // Balanced speed/accuracy
        val FAST = BubbleDetectorConfig(
            confidenceThreshold = 0.20f,
            maxDetections = 30,
            inputSize = 1024,
            useGpu = false                // CPU is faster
        )

        // Higher accuracy, lower threshold
        val ACCURATE = BubbleDetectorConfig(
            confidenceThreshold = 0.10f,
            maxDetections = 100,
            inputSize = 1024,
            useGpu = false
        )
        
        // Debug mode - very low threshold to see what the model outputs
        val DEBUG = BubbleDetectorConfig(
            confidenceThreshold = 0.01f,
            maxDetections = 200,
            inputSize = 1024,
            useGpu = false
        )
    }
}
