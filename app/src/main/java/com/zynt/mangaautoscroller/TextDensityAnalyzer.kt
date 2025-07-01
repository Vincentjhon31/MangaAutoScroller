package com.zynt.mangaautoscroller

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Helper class to analyze text density in manga images for adaptive scrolling
 */
class TextDensityAnalyzer(private val context: Context) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Analyzes an image and returns a "density factor" between 0.0 and 1.0
     * 0.0 = no text, fast scrolling
     * 1.0 = lots of text, slow scrolling
     */
    suspend fun analyzeImageTextDensity(imageUri: Uri): Float {
        return withContext(Dispatchers.IO) {
            try {
                val inputImage = InputImage.fromFilePath(context, imageUri)

                suspendCancellableCoroutine { continuation ->
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            // Calculate text density based on:
                            // 1. Number of text blocks
                            // 2. Total text length
                            val blockCount = visionText.textBlocks.size
                            val totalTextLength = visionText.text.length

                            // Calculate a density factor (adjust these thresholds based on testing)
                            val textFactor = when {
                                totalTextLength > 200 -> 1.0f // Maximum slowdown
                                totalTextLength > 100 -> 0.7f // Significant slowdown
                                totalTextLength > 50 -> 0.4f  // Moderate slowdown
                                totalTextLength > 10 -> 0.2f  // Slight slowdown
                                else -> 0.0f                  // No slowdown
                            }

                            continuation.resume(textFactor)
                        }
                        .addOnFailureListener {
                            // Default to medium speed if analysis fails
                            continuation.resume(0.3f)
                        }
                }
            } catch (e: Exception) {
                // Default to medium speed on error
                0.3f
            }
        }
    }
}
