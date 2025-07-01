package com.zynt.mangaautoscroller.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zynt.mangaautoscroller.MainActivity
import com.zynt.mangaautoscroller.R
import kotlinx.coroutines.*
import android.content.pm.ServiceInfo

/**
 * Simplified ScrollerOverlayService that eliminates crash-causing dependencies
 */
class ScrollerOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var scrollJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Scrolling parameters
    private var baseScrollSpeed = 2000L
    private var isScrolling = false
    private var currentTextDensity = 0.0f

    // Touch parameters for dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var layoutParams: WindowManager.LayoutParams? = null

    // Adaptive scrolling parameters
    private var adaptiveScrollingEnabled = true
    private var textDensityHistory = mutableListOf<Float>()
    private var lastTextAnalysisTime = 0L
    private val textAnalysisInterval = 1500L // Analyze every 1.5 seconds
    private var averageTextDensity = 0.0f

    // Text detection sensitivity and response settings
    private var textDetectionSensitivity = 1.0f // Range: 0.5-2.0, default 1.0
    private var adaptiveResponseStrength = 1.0f // Range: 0.5-2.0, default 1.0

    // Current direction
    private var currentDirection = ScrollerAccessibilityService.DIRECTION_DOWN

    // Add these new variables at the top of the class
    private var isMinimized = false
    private var overlayOpacity = 0.85f
    private var isExpanded = false

    // SharedPreferences key constants
    companion object {
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "ScrollerOverlayChannel"
        private const val TAG = "ScrollerOverlayService"

        // Preference keys
        private const val PREFS_NAME = "MangaScrollerPrefs"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_DIRECTION = "scroll_direction"
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_ADAPTIVE_ENABLED = "adaptive_enabled"
        private const val KEY_SENSITIVITY = "text_sensitivity"
        private const val KEY_RESPONSE_STRENGTH = "response_strength"
    }

    // OCR and MediaProjection variables
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var ocrEnabled = false

    // OCR Recognizer
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var ocrCaptureJob: Job? = null

    // OCR Status tracking
    private var ocrStatus = OCRStatus.DISABLED
    private var lastOCRProcessTime = 0L
    private var ocrSuccessCount = 0
    private var ocrFailureCount = 0
    private var lastOCRError: String? = null
    private var ocrStartTime = 0L
    private var lastDetectedText = "No text detected yet"
    private var detectionMethod = "None"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()

        // Load saved settings when service is created
        loadSavedSettings()
    }

    // Add methods to save and load settings
    private fun loadSavedSettings() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Load scroll speed with type migration handling
            try {
                baseScrollSpeed = prefs.getLong(KEY_SCROLL_SPEED, 2000L)
            } catch (e: ClassCastException) {
                // The value might have been saved as a Float previously
                Log.w(TAG, "Migrating scroll speed from Float to Long")
                val floatValue = prefs.getFloat(KEY_SCROLL_SPEED, 2000f)
                baseScrollSpeed = floatValue.toLong()
                // Save it back as Long to prevent future errors
                prefs.edit().putLong(KEY_SCROLL_SPEED, baseScrollSpeed).apply()
            }

            // Load other settings
            currentDirection = prefs.getInt(KEY_DIRECTION, ScrollerAccessibilityService.DIRECTION_DOWN)
            overlayOpacity = prefs.getFloat(KEY_OPACITY, 0.85f)
            adaptiveScrollingEnabled = prefs.getBoolean(KEY_ADAPTIVE_ENABLED, true)
            textDetectionSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f)
            adaptiveResponseStrength = prefs.getFloat(KEY_RESPONSE_STRENGTH, 1.0f)

            Log.d(TAG, "Settings loaded: speed=$baseScrollSpeed, direction=$currentDirection")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings", e)
            // If loading fails, we'll use the default values
        }
    }

    private fun saveSettings() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // Save all current settings
            editor.putLong(KEY_SCROLL_SPEED, baseScrollSpeed)
            editor.putInt(KEY_DIRECTION, currentDirection)
            editor.putFloat(KEY_OPACITY, overlayOpacity)
            editor.putBoolean(KEY_ADAPTIVE_ENABLED, adaptiveScrollingEnabled)
            editor.putFloat(KEY_SENSITIVITY, textDetectionSensitivity)
            editor.putFloat(KEY_RESPONSE_STRENGTH, adaptiveResponseStrength)

            // Apply changes
            editor.apply()

            Log.d(TAG, "Settings saved: speed=$baseScrollSpeed, direction=$currentDirection")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting...")

        when (intent?.action) {
            "STOP_SERVICE" -> {
                stopOCRCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            "PAUSE" -> {
                stopScrolling()
                return START_STICKY
            }
            "PLAY" -> {
                startScrolling()
                return START_STICKY
            }
        }

        try {
            // Start foreground service with correct type for media projection FIRST
            // This must happen before any MediaProjection operations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            // Extract settings from intent
            intent?.let { serviceIntent ->
                baseScrollSpeed = serviceIntent.getLongExtra("scroll_speed", 2000L)
                overlayOpacity = serviceIntent.getFloatExtra("overlay_opacity", 0.85f)
                adaptiveScrollingEnabled = serviceIntent.getBooleanExtra("adaptive_enabled", true)
                textDetectionSensitivity = serviceIntent.getFloatExtra("text_sensitivity", 1.0f)
                adaptiveResponseStrength = serviceIntent.getFloatExtra("response_strength", 1.0f)
                ocrEnabled = serviceIntent.getBooleanExtra("ocr_enabled", false)

                // Setup OCR if enabled and MediaProjection data is available
                val mediaProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    serviceIntent.getParcelableExtra("media_projection_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    serviceIntent.getParcelableExtra("media_projection_data")
                }

                if (ocrEnabled && mediaProjectionData != null) {
                    setupOCRCapture(mediaProjectionData)
                    Log.d(TAG, "✅ OCR capture setup completed")
                } else {
                    Log.i(TAG, "OCR not enabled or MediaProjection data not available")
                }
            }


            // Create simple overlay
            createSimpleOverlay()

            Log.d(TAG, "✅ Service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting service", e)
            // Don't crash - just stop the service gracefully
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /**
     * Setup OCR screen capture using MediaProjection
     */
    private fun setupOCRCapture(mediaProjectionData: Intent) {
        try {
            updateOCRStatus(OCRStatus.INITIALIZING)
            ocrStartTime = System.currentTimeMillis()

            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionData)

            // Get screen dimensions
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()

            // Use the WindowManager to get display metrics instead of trying to access display directly
            // This works in a service context without requiring a direct display association
            val defaultDisplay = windowManager.defaultDisplay
            defaultDisplay.getMetrics(metrics)

            screenDensity = metrics.densityDpi
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            // Create ImageReader for screen capture
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            // Register a MediaProjection callback - required starting with Android 12
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped")
                        stopOCRCapture()
                    }
                }, Handler(Looper.getMainLooper()))
            }
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaOCRCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            // Start periodic OCR capture
            startOCRCaptureLoop()
            updateOCRStatus(OCRStatus.READY)

            Log.d(TAG, "OCR capture setup completed: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up OCR capture", e)
            lastOCRError = e.message
            updateOCRStatus(OCRStatus.ERROR)
            ocrEnabled = false
        }
    }

    /**
     * Start the OCR capture loop
     */
    private fun startOCRCaptureLoop() {
        ocrCaptureJob = coroutineScope.launch {
            while (ocrEnabled && isActive && virtualDisplay != null) {
                try {
                    delay(textAnalysisInterval) // Wait between captures
                    if (adaptiveScrollingEnabled && isScrolling) {
                        captureAndProcessScreen()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in OCR capture loop", e)
                    break
                }
            }
        }
    }

    /**
     * Capture screen and process with ML Kit OCR
     */
    private fun captureAndProcessScreen() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()

                bitmap?.let { bmp ->
                    processImageWithMLKit(bmp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing and processing screen", e)
        }
    }

    /**
     * Convert Image to Bitmap for ML Kit processing
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Return the exact size bitmap
            return if (rowPadding == 0) {
                bitmap
            } else {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                croppedBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap", e)
            return null
        }
    }

    /**
     * Process bitmap with ML Kit Text Recognition
     */
    private fun processImageWithMLKit(bitmap: Bitmap) {
        try {
            updateOCRStatus(OCRStatus.PROCESSING)
            lastOCRProcessTime = System.currentTimeMillis()

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    // Successfully extracted text
                    val extractedText = visionText.text
                    val textBlocks = visionText.textBlocks

                    // Update success count and status
                    ocrSuccessCount++
                    updateOCRStatus(OCRStatus.READY)

                    Log.d(TAG, "OCR Success: Found ${textBlocks.size} text blocks")
                    Log.v(TAG, "Extracted text: $extractedText")

                    // Analyze the text for adaptive scrolling
                    analyzeOCRResults(extractedText, textBlocks)

                    // Clean up bitmap
                    bitmap.recycle()
                }
                .addOnFailureListener { exception ->
                    // OCR failed - update failure count and status
                    ocrFailureCount++
                    lastOCRError = exception.message
                    updateOCRStatus(OCRStatus.ERROR)

                    Log.w(TAG, "OCR failed", exception)
                    bitmap.recycle()

                    // Return to READY status after a brief delay
                    coroutineScope.launch {
                        delay(2000) // Wait 2 seconds before marking as ready again
                        if (ocrStatus == OCRStatus.ERROR) {
                            updateOCRStatus(OCRStatus.READY)
                        }
                    }
                }
        } catch (e: Exception) {
            ocrFailureCount++
            lastOCRError = e.message
            updateOCRStatus(OCRStatus.ERROR)

            Log.e(TAG, "Error processing image with ML Kit", e)
            bitmap.recycle()
        }
    }

    /**
     * Analyze OCR results for adaptive scrolling decisions
     */
    private fun analyzeOCRResults(text: String, textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>) {
        try {
            // Calculate text density based on actual OCR results
            val textDensity = calculateTextDensityFromOCR(textBlocks)

            // Check for end-of-chapter/page indicators
            val isEndOfPage = detectEndOfPage(text)

            // Check for dense dialogue sections
            val isDenseText = detectDenseText(textBlocks)

            // Update text density history
            textDensityHistory.add(textDensity)
            if (textDensityHistory.size > 5) {
                textDensityHistory.removeAt(0)
            }

            // Calculate average text density
            averageTextDensity = textDensityHistory.average().toFloat()
            currentTextDensity = textDensity

            // Log analysis results
            Log.d(TAG, "OCR Analysis: density=$textDensity, endOfPage=$isEndOfPage, dense=$isDenseText")

            // Apply adaptive scrolling decisions
            if (isEndOfPage) {
                Log.i(TAG, "End of page detected - pausing scroll")
                // Optionally pause scrolling or slow it down significantly
                // stopScrolling() // Uncomment if you want to auto-pause at end of page
            } else if (isDenseText) {
                Log.i(TAG, "Dense text detected - using slower scroll speed")
                // The adaptive speed calculation will handle this automatically
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing OCR results", e)
        }
    }

    /**
     * Calculate text density from actual OCR results
     */
    private fun calculateTextDensityFromOCR(textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>): Float {
        if (textBlocks.isEmpty()) return 0.0f

        try {
            // Calculate the total area covered by text
            var totalTextArea = 0
            val screenArea = screenWidth * screenHeight

            for (block in textBlocks) {
                val boundingBox = block.boundingBox
                if (boundingBox != null) {
                    val area = boundingBox.width() * boundingBox.height()
                    totalTextArea += area
                }
            }

            // Calculate density as percentage of screen covered by text
            val baseDensity = totalTextArea.toFloat() / screenArea.toFloat()

            // Apply sensitivity multiplier
            return baseDensity * textDetectionSensitivity
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating text density from OCR", e)
            return 0.0f
        }
    }

    /**
     * Detect end of page/chapter indicators in text
     */
    private fun detectEndOfPage(text: String): Boolean {
        val endIndicators = listOf(
            "end of chapter",
            "to be continued",
            "continued next time",
            "next chapter",
            "chapter end",
            "終わり", // Japanese: end
            "続く", // Japanese: to be continued
            "次回", // Japanese: next time
            "第.*話.*終", // Japanese: end of episode/chapter
            "fin",
            "the end"
        )

        val lowerText = text.lowercase()
        return endIndicators.any { indicator ->
            lowerText.contains(indicator.lowercase())
        }
    }

    /**
     * Detect dense text sections (lots of dialogue)
     */
    private fun detectDenseText(textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>): Boolean {
        // Consider it dense if there are many text blocks or large blocks
        val blockCount = textBlocks.size
        val hasLargeBlocks = textBlocks.any { block ->
            val boundingBox = block.boundingBox
            boundingBox != null && boundingBox.width() > screenWidth * 0.3
        }

        return blockCount > 5 || hasLargeBlocks
    }

    /**
     * Stop OCR capture and cleanup resources
     */
    private fun stopOCRCapture() {
        try {
            ocrCaptureJob?.cancel()
            virtualDisplay?.release()
            mediaProjection?.stop()
            imageReader?.close()

            virtualDisplay = null
            mediaProjection = null
            imageReader = null

            Log.d(TAG, "OCR capture stopped and resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping OCR capture", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Service destroying...")

        try {
            stopScrolling()
            stopOCRCapture()
            overlayView?.let { windowManager?.removeView(it) }
            coroutineScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction", e)
        }

        Log.d(TAG, "✅ Service destroyed successfully")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createRoundedBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(0xFFF7F2FA.toInt()) // Material 3 surface
            setStroke(2, 0xFFE7E0EC.toInt()) // Material 3 outline
        }
    }

    private fun createCircleBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun createButtonBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(color)
        }
    }

    /**
     * Analyzes the current screen to determine text density
     */
    private fun analyzeScreenForTextDensity() {
        try {
            // Use OCR if available, otherwise use simulated analysis
            if (ocrEnabled && virtualDisplay != null) {
                // OCR analysis is handled in the OCR capture loop
                return
            }

            // Fallback to simulated analysis for non-OCR mode
            val bitmap = captureScreenshot() ?: return
            val textDensity = calculateTextDensityFromBitmap(bitmap)

            // Update text density history
            textDensityHistory.add(textDensity)
            if (textDensityHistory.size > 5) {
                textDensityHistory.removeAt(0)
            }

            // Calculate average text density
            averageTextDensity = textDensityHistory.average().toFloat()
            currentTextDensity = textDensity

            // Clean up
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen for text density", e)
        }
    }

    /**
     * Calculate text density from bitmap image with improved detection
     */
    private fun calculateTextDensityFromBitmap(bitmap: android.graphics.Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height

        // Adjusted for sensitivity settings
        val threshold = 128 - ((textDetectionSensitivity - 1.0f) * 40).toInt()
        val samplingRate = (11 - textDetectionSensitivity * 5).toInt().coerceAtLeast(1)

        var darkPixels = 0
        var sampledPixels = 0

        // Sample pixels based on sensitivity settings
        for (x in 0 until width step samplingRate) {
            for (y in 0 until height step samplingRate) {
                sampledPixels++
                val pixel = bitmap.getPixel(x, y)

                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)

                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

                if (brightness < threshold) {
                    darkPixels++
                }

                // Look for edges (common in text)
                if (x < width - samplingRate && y < height - samplingRate) {
                    val pixelRight = bitmap.getPixel(x + samplingRate, y)
                    val pixelDown = bitmap.getPixel(x, y + samplingRate)

                    val brightRight = (android.graphics.Color.red(pixelRight) * 0.299 +
                            android.graphics.Color.green(pixelRight) * 0.587 +
                            android.graphics.Color.blue(pixelRight) * 0.114).toInt()

                    val brightDown = (android.graphics.Color.red(pixelDown) * 0.299 +
                            android.graphics.Color.green(pixelDown) * 0.587 +
                            android.graphics.Color.blue(pixelDown) * 0.114).toInt()

                    if (Math.abs(brightness - brightRight) > 40 || Math.abs(brightness - brightDown) > 40) {
                        darkPixels++
                    }
                }
            }
        }

        val baseDensity = darkPixels.toFloat() / sampledPixels.coerceAtLeast(1)
        return baseDensity * textDetectionSensitivity
    }

    /**
     * Enhanced simulated screenshot for fallback mode
     */
    private fun captureScreenshot(): android.graphics.Bitmap? {
        try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            return android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply {
                val canvas = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 24f
                    isAntiAlias = true
                }

                val random = java.util.Random()

                // Create text-like regions
                val numPanels = random.nextInt(4) + 2
                for (i in 0 until numPanels) {
                    val panelX = random.nextInt(width - 200)
                    val panelY = random.nextInt(height - 200)
                    val panelWidth = random.nextInt(200) + 100
                    val panelHeight = random.nextInt(200) + 100

                    val numLines = random.nextInt(6) + 1
                    for (line in 0 until numLines) {
                        val lineY = panelY + (line + 1) * panelHeight / (numLines + 1)
                        val lineWidth = random.nextInt(panelWidth - 20) + 20
                        canvas.drawLine(
                            panelX + 10f,
                            lineY.toFloat(),
                            (panelX + 10 + lineWidth).toFloat(),
                            lineY.toFloat(),
                            paint
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating simulated screenshot", e)
            return null
        }
    }

    /**
     * Calculate adaptive scroll speed based on text density
     */
    private fun calculateAdaptiveScrollSpeed(): Long {
        val normalizedDensity = minOf(1.0f, maxOf(0.0f, currentTextDensity * 5))
        val adjustedDensity = normalizedDensity * adaptiveResponseStrength
        val adjustmentFactor = 2.0f - adjustedDensity * 1.5f
        val adaptiveSpeed = (baseScrollSpeed / adjustmentFactor).toLong()

        val minSpeed = (500 * (1 / adaptiveResponseStrength)).toLong().coerceAtLeast(300)
        val maxSpeed = (6000 * adaptiveResponseStrength).toLong().coerceAtMost(8000)

        return minOf(maxSpeed, maxOf(minSpeed, adaptiveSpeed))
    }

    private fun createSimpleOverlay() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = createModernOverlayView()

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            windowManager?.addView(overlayView, layoutParams)

            // Add touch listener for drag functionality
            overlayView?.setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            layoutParams!!.x = initialX + dx.toInt()
                            layoutParams!!.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(view, layoutParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y
                            val moved = Math.abs(event.rawX - initialTouchX) > 5 ||
                                       Math.abs(event.rawY - initialTouchY) > 5
                            return moved
                        }
                    }
                    return false
                }
            })

            Log.d(TAG, "✅ Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create overlay", e)
        }
    }

    private fun createModernOverlayView(): View {
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = createRoundedBackground()
            elevation = 12f
        }

        // Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = View(this).apply {
            background = createCircleBackground(0xFF6750A4.toInt())
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                setMargins(0, 0, 16, 0)
            }
        }

        val titleText = TextView(this).apply {
            text = "🎌 Manga Scroller"
            textSize = 16f
            setTextColor(0xFF1C1B1F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeButton = Button(this).apply {
            text = "➖"
            textSize = 12f
            background = createButtonBackground(0xFF79747E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(48, 48)
            setOnClickListener { toggleMinimizedView() }
        }

        headerLayout.addView(iconView)
        headerLayout.addView(titleText)
        headerLayout.addView(minimizeButton)

        // Status layout
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
            tag = "statusLayout"
        }

        val statusDot = View(this).apply {
            background = createCircleBackground(if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
            layoutParams = LinearLayout.LayoutParams(24, 24).apply {
                setMargins(0, 0, 12, 0)
            }
            tag = "statusDot"
        }

        val statusText = TextView(this).apply {
            text = if (isScrolling) "Active" else "Paused"
            textSize = 14f
            setTextColor(if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
            tag = "statusText"
        }

        statusLayout.addView(statusDot)
        statusLayout.addView(statusText)

        // Button layout
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            tag = "buttonLayout"
        }

        val playPauseButton = Button(this).apply {
            text = if (isScrolling) "⏸️ Pause" else "▶️ Play"
            textSize = 14f
            background = createButtonBackground(0xFF6750A4.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 8, 0)
            }
            tag = "playPauseButton"
            setOnClickListener {
                if (isScrolling) {
                    stopScrolling()
                } else {
                    startScrolling()
                }
                updateOverlayUI()
            }
        }

        val settingsButton = Button(this).apply {
            text = "⚙️"
            textSize = 16f
            background = createButtonBackground(0xFF625B71.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(56, 56).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { toggleExpandedView() }
        }

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 14f
            background = createButtonBackground(0xFFB3261E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(56, 56)
            setOnClickListener { stopSelf() }
        }

        buttonLayout.addView(playPauseButton)
        buttonLayout.addView(settingsButton)
        buttonLayout.addView(closeButton)

        // Expanded layout (initially hidden)
        val expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
            tag = "expandedLayout"
        }

        // Add sections
        expandedLayout.addView(createSpeedControlSection())
        expandedLayout.addView(createDirectionControlSection())
        expandedLayout.addView(createAdaptiveScrollingSection())
        expandedLayout.addView(createOCRStatusSection())
        expandedLayout.addView(createOpacityControlSection())

        // Assemble the complete overlay
        mainContainer.addView(headerLayout)
        mainContainer.addView(statusLayout)
        mainContainer.addView(buttonLayout)
        mainContainer.addView(expandedLayout)

        return mainContainer
    }

    private fun createSpeedControlSection(): LinearLayout {
        val speedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val speedLabel = TextView(this).apply {
            text = "📊 Scroll Speed"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val speedButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "speedButtons"
        }

        val speeds = listOf("0.5x" to 4000L, "1x" to 2000L, "2x" to 1000L, "3x" to 666L)
        speeds.forEach { (label, speed) ->
            val speedButton = Button(this).apply {
                text = label
                textSize = 11f
                background = createButtonBackground(
                    if (baseScrollSpeed == speed) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                )
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    baseScrollSpeed = speed
                    updateSpeedButtons()
                    saveSettings()
                    Toast.makeText(this@ScrollerOverlayService, "Speed: $label", Toast.LENGTH_SHORT).show()
                }
            }
            speedButtonsLayout.addView(speedButton)
        }

        speedLayout.addView(speedLabel)
        speedLayout.addView(speedButtonsLayout)
        return speedLayout
    }

    private fun createDirectionControlSection(): LinearLayout {
        val directionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val directionLabel = TextView(this).apply {
            text = "🧭 Scroll Direction"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val directionButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "directionButtons"
        }

        val directions = listOf(
            "⬆️" to ScrollerAccessibilityService.DIRECTION_UP,
            "⬇️" to ScrollerAccessibilityService.DIRECTION_DOWN,
            "⬅️" to ScrollerAccessibilityService.DIRECTION_LEFT,
            "➡️" to ScrollerAccessibilityService.DIRECTION_RIGHT
        )

        directions.forEach { (emoji, direction) ->
            val directionButton = Button(this).apply {
                text = emoji
                textSize = 14f
                background = createButtonBackground(
                    if (currentDirection == direction) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                )
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    currentDirection = direction
                    updateDirectionButtons()
                    saveSettings()
                    val dirName = when (direction) {
                        ScrollerAccessibilityService.DIRECTION_UP -> "Up"
                        ScrollerAccessibilityService.DIRECTION_DOWN -> "Down"
                        ScrollerAccessibilityService.DIRECTION_LEFT -> "Left"
                        ScrollerAccessibilityService.DIRECTION_RIGHT -> "Right"
                        else -> "Down"
                    }
                    Toast.makeText(this@ScrollerOverlayService, "Direction: $dirName", Toast.LENGTH_SHORT).show()
                }
            }
            directionButtonsLayout.addView(directionButton)
        }

        directionLayout.addView(directionLabel)
        directionLayout.addView(directionButtonsLayout)
        return directionLayout
    }

    private fun createAdaptiveScrollingSection(): LinearLayout {
        val adaptiveLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val adaptiveLabel = TextView(this).apply {
            text = "♻️ Adaptive Scrolling"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val switchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val switchLabel = TextView(this).apply {
            text = "Enabled"
            textSize = 12f
            setTextColor(0xFF49454F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val adaptiveSwitch = Switch(this).apply {
            isChecked = adaptiveScrollingEnabled
            setOnCheckedChangeListener { _, isChecked ->
                adaptiveScrollingEnabled = isChecked
                saveSettings()
                Toast.makeText(
                    this@ScrollerOverlayService,
                    if (isChecked) "Adaptive Scrolling: ON" else "Adaptive Scrolling: OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        switchLayout.addView(switchLabel)
        switchLayout.addView(adaptiveSwitch)

        adaptiveLayout.addView(adaptiveLabel)
        adaptiveLayout.addView(switchLayout)

        return adaptiveLayout
    }

    private fun createOCRStatusSection(): LinearLayout {
        val ocrStatusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val ocrStatusLabel = TextView(this).apply {
            text = "🔍 OCR Status & Detection"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val ocrStatusText = TextView(this).apply {
            text = getOCRStatusInfo()
            textSize = 11f
            setTextColor(getOCRStatusColor())
            tag = "ocrStatusText"
            setPadding(0, 0, 0, 4)
        }

        val detectionMethodText = TextView(this).apply {
            text = "Method: $detectionMethod"
            textSize = 10f
            setTextColor(0xFF6B7280.toInt())
            tag = "detectionMethodText"
            setPadding(0, 0, 0, 4)
        }

        val detectedTextLabel = TextView(this).apply {
            text = "Last Detected:"
            textSize = 10f
            setTextColor(0xFF4B5563.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 4, 0, 2)
        }

        val detectedTextContent = TextView(this).apply {
            text = lastDetectedText
            textSize = 9f
            setTextColor(0xFF6B7280.toInt())
            tag = "detectedTextContent"
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8, 0, 0, 0)
        }

        ocrStatusLayout.addView(ocrStatusLabel)
        ocrStatusLayout.addView(ocrStatusText)
        ocrStatusLayout.addView(detectionMethodText)
        ocrStatusLayout.addView(detectedTextLabel)
        ocrStatusLayout.addView(detectedTextContent)

        return ocrStatusLayout
    }

    /**
     * Get OCR status color based on current status
     */
    private fun getOCRStatusColor(): Int {
        return when (ocrStatus) {
            OCRStatus.READY, OCRStatus.PROCESSING -> 0xFF4CAF50.toInt() // Green
            OCRStatus.INITIALIZING -> 0xFF2196F3.toInt() // Blue
            OCRStatus.ERROR -> 0xFFFF5722.toInt() // Red
            OCRStatus.STOPPED, OCRStatus.DISABLED -> 0xFF757575.toInt() // Gray
        }
    }

    private fun createOpacityControlSection(): LinearLayout {
        val opacityLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val opacityLabel = TextView(this).apply {
            text = "🔅 Overlay Opacity"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val opacityButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "opacityButtons"
        }

        val opacities = listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "100%" to 1.0f)
        opacities.forEach { (label, opacity) ->
            val opacityButton = Button(this).apply {
                text = label
                textSize = 10f
                tag = opacity
                background = createButtonBackground(
                    if (overlayOpacity == opacity) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                )
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(10, 6, 10, 6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    updateOverlayOpacity(opacity)
                    updateOpacityButtons()
                    Toast.makeText(this@ScrollerOverlayService, "Opacity: $label", Toast.LENGTH_SHORT).show()
                }
            }
            opacityButtonsLayout.addView(opacityButton)
        }

        opacityLayout.addView(opacityLabel)
        opacityLayout.addView(opacityButtonsLayout)
        return opacityLayout
    }

    private fun toggleMinimizedView() {
        isMinimized = !isMinimized
        (overlayView as? LinearLayout)?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                when (child.tag) {
                    "statusLayout", "buttonLayout", "expandedLayout" -> {
                        child.visibility = if (isMinimized) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateOverlayOpacity(opacity: Float) {
        overlayOpacity = opacity
        overlayView?.alpha = opacity
    }

    private fun updateSpeedButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "speedButtons") { view ->
                if (view is Button) {
                    val isSelected = when (view.text) {
                        "0.5x" -> baseScrollSpeed == 4000L
                        "1x" -> baseScrollSpeed == 2000L
                        "2x" -> baseScrollSpeed == 1000L
                        "3x" -> baseScrollSpeed == 666L
                        else -> false
                    }
                    view.background = createButtonBackground(
                        if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                    )
                }
            }
        }
    }

    private fun updateDirectionButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "directionButtons") { view ->
                if (view is Button) {
                    val isSelected = when (view.text) {
                        "⬆️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_UP
                        "⬇️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_DOWN
                        "⬅️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_LEFT
                        "➡️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_RIGHT
                        else -> false
                    }
                    view.background = createButtonBackground(
                        if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                    )
                }
            }
        }
    }

    private fun updateOpacityButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "opacityButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val buttonOpacity = button.tag as? Float
                            val isSelected = buttonOpacity == overlayOpacity
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun findViewsByTag(parent: ViewGroup, tag: String, action: (View) -> Unit) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == tag) {
                action(child)
            }
            if (child is ViewGroup) {
                findViewsByTag(child, tag, action)
            }
        }
    }

    private fun toggleExpandedView() {
        (overlayView as? LinearLayout)?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.tag == "expandedLayout") {
                    isExpanded = !isExpanded
                    child.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    break
                }
            }
        }
    }

    private fun startScrolling() {
        isScrolling = true
        updateOverlayUI()

        scrollJob = coroutineScope.launch {
            while (isScrolling && isActive) {
                try {
                    performScrollAction()
                    delay(baseScrollSpeed)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during scrolling", e)
                    break
                }
            }
        }

        Log.d(TAG, "🚀 Scrolling started")
    }

    private fun stopScrolling() {
        isScrolling = false
        updateOverlayUI()
        scrollJob?.cancel()
        Log.d(TAG, "⏸️ Scrolling stopped")
    }

    private fun updateOverlayUI() {
        try {
            (overlayView as? LinearLayout)?.let { container ->
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is LinearLayout && child.childCount >= 2) {
                        val secondChild = child.getChildAt(1)
                        if (secondChild is TextView && (secondChild.text == "Active" || secondChild.text == "Paused")) {
                            val statusDot = child.getChildAt(0) as View
                            statusDot.background = createCircleBackground(
                                if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
                            )
                            secondChild.text = if (isScrolling) "Active" else "Paused"
                            secondChild.setTextColor(if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
                        }

                        if (child.childCount >= 3 && child.getChildAt(0) is Button) {
                            val playPauseButton = child.getChildAt(0) as Button
                            playPauseButton.text = if (isScrolling) "⏸️ Pause" else "▶️ Play"
                            playPauseButton.background = createButtonBackground(
                                if (isScrolling) 0xFF4CAF50.toInt() else 0xFF6750A4.toInt()
                            )
                        }
                    }
                }

                updateSpeedButtons()
                updateDirectionButtons()
            }

            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Could not update overlay UI", e)
        }
    }

    private fun performScrollAction() {
        val accessibilityService = ScrollerAccessibilityService.getInstance()
        if (accessibilityService != null) {
            if (adaptiveScrollingEnabled) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTextAnalysisTime > textAnalysisInterval) {
                    analyzeScreenForTextDensity()
                    lastTextAnalysisTime = currentTime
                }

                val adjustedSpeed = calculateAdaptiveScrollSpeed()
                accessibilityService.performScroll(currentDirection, adjustedSpeed)
            } else {
                accessibilityService.performScroll(currentDirection, baseScrollSpeed)
            }
        } else {
            Log.w(TAG, "⚠️ Accessibility service not available")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Manga Auto Scroller",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Smart adaptive scrolling for manga"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎌 Manga Auto Scroller Active")
            .setContentText("Status: ${if (isScrolling) "Scrolling" else "Paused"}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isScrolling) "Pause" else "Play",
                createToggleIntent()
            )
            .build()
    }

    private fun createToggleIntent(): PendingIntent {
        val intent = Intent(this, ScrollerOverlayService::class.java).apply {
            action = if (isScrolling) "PAUSE" else "PLAY"
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Update OCR status and handle logging/UI feedback
     */
    private fun updateOCRStatus(newStatus: OCRStatus) {
        val previousStatus = ocrStatus
        ocrStatus = newStatus

        // Log status change with detailed information
        when (newStatus) {
            OCRStatus.DISABLED -> {
                Log.i(TAG, "🔴 OCR Status: DISABLED")
            }
            OCRStatus.INITIALIZING -> {
                Log.i(TAG, "🟡 OCR Status: INITIALIZING - Setting up MediaProjection and ImageReader")
            }
            OCRStatus.READY -> {
                val setupTime = System.currentTimeMillis() - ocrStartTime
                Log.i(TAG, "🟢 OCR Status: READY - Setup completed in ${setupTime}ms")
            }
            OCRStatus.PROCESSING -> {
                Log.d(TAG, "🔵 OCR Status: PROCESSING - Analyzing screen content")
            }
            OCRStatus.ERROR -> {
                val errorMsg = lastOCRError ?: "Unknown error"
                Log.e(TAG, "🔴 OCR Status: ERROR - $errorMsg")
            }
            OCRStatus.STOPPED -> {
                Log.i(TAG, "⏹️ OCR Status: STOPPED - OCR capture halted")
            }
        }

        // Update overlay UI if needed
        if (previousStatus != newStatus) {
            updateOCRStatusInUI()
        }
    }

    /**
     * Get current OCR status information
     */
    fun getOCRStatusInfo(): String {
        return when (ocrStatus) {
            OCRStatus.DISABLED -> "OCR: Disabled"
            OCRStatus.INITIALIZING -> "OCR: Starting..."
            OCRStatus.READY -> "OCR: Ready (${getOCRSuccessRate()}% success)"
            OCRStatus.PROCESSING -> "OCR: Processing..."
            OCRStatus.ERROR -> "OCR: Error - ${lastOCRError}"
            OCRStatus.STOPPED -> "OCR: Stopped"
        }
    }

    /**
     * Calculate OCR success rate
     */
    private fun getOCRSuccessRate(): Int {
        val totalAttempts = ocrSuccessCount + ocrFailureCount
        return if (totalAttempts > 0) {
            ((ocrSuccessCount.toFloat() / totalAttempts) * 100).toInt()
        } else {
            0
        }
    }

    /**
     * Get OCR runtime information
     */
    fun getOCRRuntimeInfo(): String {
        if (ocrStatus == OCRStatus.DISABLED) return "OCR not active"

        val runtime = System.currentTimeMillis() - ocrStartTime
        val runtimeSeconds = runtime / 1000
        val lastProcessSeconds = (System.currentTimeMillis() - lastOCRProcessTime) / 1000

        return "Runtime: ${runtimeSeconds}s | Last process: ${lastProcessSeconds}s ago | Success: $ocrSuccessCount | Errors: $ocrFailureCount"
    }

    /**
     * Update OCR status display in overlay UI
     */
    private fun updateOCRStatusInUI() {
        try {
            // Find and update OCR status in the overlay if it exists
            (overlayView as? LinearLayout)?.let { container ->
                findViewsByTag(container, "ocrStatusText") { view ->
                    if (view is TextView) {
                        view.text = getOCRStatusInfo()
                        // Update color based on status
                        val color = when (ocrStatus) {
                            OCRStatus.READY, OCRStatus.PROCESSING -> 0xFF4CAF50.toInt() // Green
                            OCRStatus.INITIALIZING -> 0xFF2196F3.toInt() // Blue
                            OCRStatus.ERROR -> 0xFFFF5722.toInt() // Red
                            OCRStatus.STOPPED, OCRStatus.DISABLED -> 0xFF757575.toInt() // Gray
                        }
                        view.setTextColor(color)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update OCR status in UI", e)
        }
    }

    /**
     * Enum class for OCR status tracking
     */
    private enum class OCRStatus {
        INITIALIZING,
        READY,
        PROCESSING,
        ERROR,
        STOPPED,
        DISABLED
    }
}
