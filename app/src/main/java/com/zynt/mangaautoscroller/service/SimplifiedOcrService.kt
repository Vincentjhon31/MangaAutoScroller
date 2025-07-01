package com.zynt.mangaautoscroller.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zynt.mangaautoscroller.R

/**
 * Simplified OCR Service - Alternative implementation that bypasses service type restrictions
 * This service focuses purely on OCR functionality without complex foreground service types
 */
class SimplifiedOcrService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var screenWidth = 0
    private var screenHeight = 0
    private var isOcrActive = false

    companion object {
        private const val NOTIFICATION_ID = 2000
        private const val CHANNEL_ID = "SimplifiedOcrChannel"
        private const val TAG = "SimplifiedOcrService"

        // Static callback for communicating with main service
        var onTextDetected: ((String, Int) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SimplifiedOcrService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildSimpleNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SimplifiedOcrService onStartCommand")

        when (intent?.action) {
            "START_OCR" -> {
                val mediaProjectionData = intent.getParcelableExtra<Intent>("MEDIA_PROJECTION_DATA")
                val resultCode = intent.getIntExtra("RESULT_CODE", -1)

                if (mediaProjectionData != null && resultCode == Activity.RESULT_OK) {
                    setupSimpleMediaProjection(mediaProjectionData, resultCode)
                } else {
                    Log.e(TAG, "Invalid MediaProjection data")
                    stopSelf()
                }
            }
            "STOP_OCR" -> {
                stopOcr()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun setupSimpleMediaProjection(data: Intent, resultCode: Int) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)

            // Get screen dimensions
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            // Create image reader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 1
            )

            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SimplifiedOCR",
                screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            isOcrActive = true
            Log.d(TAG, "Simplified MediaProjection setup complete")
            Toast.makeText(this, "✅ Simple OCR Active", Toast.LENGTH_SHORT).show()

            // Start OCR processing
            startOcrCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup simplified MediaProjection", e)
            Toast.makeText(this, "❌ OCR Setup Failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startOcrCapture() {
        // Simple timer-based capture
        val handler = Handler(Looper.getMainLooper())
        val captureRunnable = object : Runnable {
            override fun run() {
                if (isOcrActive) {
                    captureAndAnalyze()
                    handler.postDelayed(this, 2000) // Capture every 2 seconds
                }
            }
        }
        handler.post(captureRunnable)
    }

    private fun captureAndAnalyze() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                // Convert to bitmap
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888
                )
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Process with ML Kit
                processWithMlKit(bitmap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture failed", e)
        }
    }

    private fun processWithMlKit(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size

                Log.d(TAG, "OCR Success: $wordCount words detected")

                // Send result back to main service
                onTextDetected?.invoke(text, wordCount)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
            }
    }

    private fun stopOcr() {
        isOcrActive = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onDestroy() {
        stopOcr()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Simple OCR Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildSimpleNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OCR Service")
            .setContentText("Processing screen text...")
            .setSmallIcon(R.drawable.ic_scroll)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
