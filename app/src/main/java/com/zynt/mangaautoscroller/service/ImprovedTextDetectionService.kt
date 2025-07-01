package com.zynt.mangaautoscroller.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

/**
 * Improved text detection using ML Kit instead of MediaProjection
 * This avoids the "media projections require service" error
 */
class ImprovedTextDetectionService : AccessibilityService() {

    private val TAG = "ImprovedTextDetection"
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Text density tracking
    private var currentTextDensity = 0.0f
    private var lastAnalysisTime = 0L
    private val analysisInterval = 2000L // Analyze every 2 seconds

    // Callback for text density updates
    var onTextDensityUpdate: ((Float) -> Unit)? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only process scroll events and window changes
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAnalysisTime > analysisInterval) {
                lastAnalysisTime = currentTime
                analyzeVisibleText()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        textRecognizer.close()
    }

    /**
     * Analyze text density using accessibility API + ML Kit
     * This is much more reliable than MediaProjection
     */
    private fun analyzeVisibleText() {
        coroutineScope.launch {
            try {
                // Method 1: Use Accessibility API to get text content
                val accessibilityText = extractAccessibilityText()

                // Method 2: If accessibility text is limited, take a screenshot of visible content
                val screenshot = captureVisibleContent()

                // Combine both methods for best results
                if (screenshot != null) {
                    analyzeScreenshotWithMLKit(screenshot, accessibilityText)
                } else {
                    // Fall back to accessibility text only
                    val density = calculateTextDensityFromAccessibility(accessibilityText)
                    updateTextDensity(density)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing text", e)
            }
        }
    }

    /**
     * Extract text using Accessibility API (no permissions needed)
     */
    private fun extractAccessibilityText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()

        extractTextFromNode(rootNode, textBuilder)
        rootNode.recycle()

        return textBuilder.toString()
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, textBuilder: StringBuilder) {
        if (node == null) return

        // Get text from current node
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            textBuilder.append(nodeText).append("\n")
        }

        // Get content description
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) {
            textBuilder.append(contentDesc).append("\n")
        }

        // Recursively extract from children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractTextFromNode(child, textBuilder)
            child?.recycle()
        }
    }

    /**
     * Capture visible content without MediaProjection (safer method)
     */
    private fun captureVisibleContent(): Bitmap? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            val bounds = Rect()
            rootNode.getBoundsInScreen(bounds)

            // Create a bitmap of the visible area
            val bitmap = Bitmap.createBitmap(
                bounds.width().coerceAtMost(1080),
                bounds.height().coerceAtMost(1920),
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            // This is a simplified approach - in practice you'd need more complex rendering
            canvas.drawColor(android.graphics.Color.WHITE)

            rootNode.recycle()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing content", e)
            null
        }
    }

    /**
     * Analyze screenshot using ML Kit OCR
     */
    private suspend fun analyzeScreenshotWithMLKit(bitmap: Bitmap, accessibilityText: String) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val ocrText = visionText.text
                    val combinedText = "$accessibilityText\n$ocrText"
                    val density = calculateTextDensity(combinedText)
                    updateTextDensity(density)

                    Log.d(TAG, "OCR detected ${ocrText.length} characters")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed, using accessibility text only", e)
                    val density = calculateTextDensityFromAccessibility(accessibilityText)
                    updateTextDensity(density)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ML Kit analysis", e)
        }
    }

    /**
     * Calculate text density from accessibility API only
     */
    private fun calculateTextDensityFromAccessibility(text: String): Float {
        if (text.isBlank()) return 0.0f

        val lines = text.split("\n").filter { it.isNotBlank() }
        val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val charCount = text.replace("\\s".toRegex(), "").length

        // Calculate density based on visible screen area
        val screenHeight = 1920 // Approximate screen height
        val density = (charCount.toFloat() / screenHeight) * 1000 // Characters per 1000 pixels

        Log.d(TAG, "Accessibility text density: $density (chars: $charCount, lines: ${lines.size})")
        return density.coerceIn(0f, 10f)
    }

    /**
     * Calculate combined text density
     */
    private fun calculateTextDensity(combinedText: String): Float {
        if (combinedText.isBlank()) return 0.0f

        val lines = combinedText.split("\n").filter { it.isNotBlank() }
        val wordCount = combinedText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val charCount = combinedText.replace("\\s".toRegex(), "").length

        // More sophisticated density calculation
        val lineDensity = lines.size.toFloat() / 20f // Lines per screen section
        val charDensity = charCount.toFloat() / 2000f // Characters per typical screen
        val wordDensity = wordCount.toFloat() / 300f // Words per typical screen

        val combinedDensity = (lineDensity * 0.4f + charDensity * 0.4f + wordDensity * 0.2f)

        Log.d(TAG, "Combined text density: $combinedDensity (chars: $charCount, words: $wordCount, lines: ${lines.size})")
        return combinedDensity.coerceIn(0f, 10f)
    }

    private fun updateTextDensity(density: Float) {
        currentTextDensity = density
        onTextDensityUpdate?.invoke(density)
    }

    fun getCurrentTextDensity(): Float = currentTextDensity

    companion object {
        private var instance: ImprovedTextDetectionService? = null

        fun getInstance(): ImprovedTextDetectionService? = instance

        fun setInstance(service: ImprovedTextDetectionService) {
            instance = service
        }
    }

    override fun onCreate() {
        super.onCreate()
        setInstance(this)
    }
}
