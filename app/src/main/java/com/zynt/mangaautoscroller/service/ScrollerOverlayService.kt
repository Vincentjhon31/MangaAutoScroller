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
import com.zynt.mangaautoscroller.ml.BubbleDetector
import com.zynt.mangaautoscroller.ml.BubbleDetectorConfig
import com.zynt.mangaautoscroller.ml.ModelManager
import com.zynt.mangaautoscroller.ml.ModelStatus
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
    private var layoutParams: WindowManager.LayoutParams? = null
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

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

    // Panel detection and smart pause variables
    private var panelDetectionEnabled = true
    private var lastDetectedPanels = mutableListOf<android.graphics.Rect>()
    private var smartPauseStrength = 1.0f // 0.0-2.0, default 1.0
    private var lastScrollAdjustmentTime = 0L
    private var currentScrollSpeed = baseScrollSpeed
    private var scrollStartTime = 0L
    private var imageComplexity = 0.5f // Default medium complexity

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

        // New preference keys for comic type features
        private const val KEY_COMIC_TYPE = "comic_type"
        private const val KEY_SCROLL_DISTANCE = "scroll_distance"
        private const val KEY_READING_SPEED = "reading_speed"
        private const val KEY_AUTO_PAUSE_ENABLED = "auto_pause_enabled"
        private const val KEY_PANEL_DETECTION_ENABLED = "panel_detection_enabled"
        private const val KEY_SMART_PAUSE_STRENGTH = "smart_pause_strength"
        private const val KEY_USER_ADJUSTMENTS = "user_adjustments"
        private const val KEY_ML_DETECTION_ENABLED = "ml_detection_enabled"
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

    // Comic type and scroll configuration
    private var comicType = "MANGA" // Default to manga
    private var scrollDistance = "MEDIUM" // Default to medium scroll distance
    private var readingSpeed = "NORMAL" // Default to normal reading speed
    private var autoPauseEnabled = true // Default to enabled

    // ML-based bubble detection (advanced detection mode)
    private var mlDetectionEnabled = false // Use ONNX bubble detector instead of ML Kit
    private var modelManager: ModelManager? = null
    private var lastBubbleCount = 0
    private var lastBubbleCoverage = 0f
    private var mlModelStatus = ModelStatus.NOT_LOADED

    // Register a MediaProjection callback - required starting with Android 12
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            stopOCRCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()

        // Load saved settings when service is created
        loadSavedSettings()

        // Initialize ML Model Manager for bubble detection
        initializeMLDetection()
    }

    /**
     * Initialize ML-based bubble detection if enabled
     */
    private fun initializeMLDetection() {
        if (mlDetectionEnabled) {
            try {
                modelManager = ModelManager.getInstance(this)
                modelManager?.addStatusListener { status ->
                    mlModelStatus = status
                    Log.d(TAG, "ML Model status: $status")
                }
                // Preload model in background with TURBO config for maximum speed
                // Using TURBO config: CPU-only (faster than NNAPI), higher threshold (0.25)
                modelManager?.initializeAsync(BubbleDetectorConfig.TURBO) { success ->
                    if (success) {
                        Log.i(TAG, "✅ ML Bubble Detector ready (TURBO mode - CPU optimized)")
                    } else {
                        Log.w(TAG, "⚠️ ML Bubble Detector failed to initialize")
                    }
                }
                Log.d(TAG, "ML Detection initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ML detection", e)
                mlDetectionEnabled = false
            }
        }
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

            // Load comic type settings
            comicType = prefs.getString(KEY_COMIC_TYPE, "MANGA") ?: "MANGA"
            scrollDistance = prefs.getString(KEY_SCROLL_DISTANCE, "MEDIUM") ?: "MEDIUM"
            readingSpeed = prefs.getString(KEY_READING_SPEED, "NORMAL") ?: "NORMAL"
            autoPauseEnabled = prefs.getBoolean(KEY_AUTO_PAUSE_ENABLED, true)

            // Load panel detection settings
            panelDetectionEnabled = prefs.getBoolean(KEY_PANEL_DETECTION_ENABLED, true)
            smartPauseStrength = prefs.getFloat(KEY_SMART_PAUSE_STRENGTH, 1.0f)

            // Load ML detection setting
            mlDetectionEnabled = prefs.getBoolean(KEY_ML_DETECTION_ENABLED, false)

            Log.d(TAG, "Settings loaded: speed=$baseScrollSpeed, direction=$currentDirection, comicType=$comicType, mlDetection=$mlDetectionEnabled")
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

            // Save comic type settings
            editor.putString(KEY_COMIC_TYPE, comicType)
            editor.putString(KEY_SCROLL_DISTANCE, scrollDistance)
            editor.putString(KEY_READING_SPEED, readingSpeed)
            editor.putBoolean(KEY_AUTO_PAUSE_ENABLED, autoPauseEnabled)

            // Save panel detection settings
            editor.putBoolean(KEY_PANEL_DETECTION_ENABLED, panelDetectionEnabled)
            editor.putFloat(KEY_SMART_PAUSE_STRENGTH, smartPauseStrength)

            // Save ML detection setting
            editor.putBoolean(KEY_ML_DETECTION_ENABLED, mlDetectionEnabled)

            // Apply changes
            editor.apply()

            Log.d(TAG, "Settings saved: speed=$baseScrollSpeed, direction=$currentDirection, comicType=$comicType, mlDetection=$mlDetectionEnabled")
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
            // Check if we have MediaProjection data for OCR or ML detection
            val hasMediaProjection = intent?.hasExtra("media_projection_data") == true
            val ocrRequested = intent?.getBooleanExtra("ocr_enabled", false) == true
            val mlRequested = intent?.getBooleanExtra("ml_detection_enabled", false) == true
            val needsScreenCapture = (ocrRequested || mlRequested) && hasMediaProjection

            // Start foreground service with correct type
            // Use MEDIA_PROJECTION when screen capture is needed (OCR or ML detection)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (needsScreenCapture) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    // Use SPECIAL_USE for basic mode (just overlay + accessibility scrolling)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }

                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(),
                    serviceType
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
                mlDetectionEnabled = serviceIntent.getBooleanExtra("ml_detection_enabled", false)

                // Initialize ML detection if enabled
                if (mlDetectionEnabled && modelManager == null) {
                    initializeMLDetection()
                }

                // Setup OCR if enabled and MediaProjection data is available
                val mediaProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    serviceIntent.getParcelableExtra("media_projection_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    serviceIntent.getParcelableExtra("media_projection_data")
                }

                // Setup screen capture if EITHER OCR or ML detection is enabled
                val needsScreenCapture = ocrEnabled || mlDetectionEnabled
                if (needsScreenCapture && mediaProjectionData != null) {
                    setupScreenCapture(mediaProjectionData)
                    Log.d(TAG, "✅ Screen capture setup completed (OCR=$ocrEnabled, ML=$mlDetectionEnabled)")
                } else if (needsScreenCapture) {
                    Log.w(TAG, "⚠️ Screen capture requested but MediaProjection data not available")
                } else {
                    Log.i(TAG, "Screen capture not needed (OCR and ML detection disabled)")
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
     * Setup screen capture using MediaProjection
     * Used for both OCR (ML Kit) and ML Bubble Detection (ONNX)
     */
    private fun setupScreenCapture(mediaProjectionData: Intent) {
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
                mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
            }
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MangaScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            // Start periodic screen capture loop
            startScreenCaptureLoop()
            updateOCRStatus(OCRStatus.READY)

            Log.d(TAG, "Screen capture setup completed: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up OCR capture", e)
            lastOCRError = e.message
            updateOCRStatus(OCRStatus.ERROR)
            ocrEnabled = false
        }
    }

    /**
     * Start the screen capture loop for OCR and/or ML detection
     */
    private fun startScreenCaptureLoop() {
        ocrCaptureJob = coroutineScope.launch {
            // Run while EITHER OCR or ML detection is enabled
            val screenCaptureEnabled = { ocrEnabled || mlDetectionEnabled }
            while (screenCaptureEnabled() && isActive && virtualDisplay != null) {
                try {
                    delay(textAnalysisInterval) // Wait between captures
                    if (adaptiveScrollingEnabled && isScrolling) {
                        captureAndProcessScreen()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in screen capture loop", e)
                    break
                }
            }
        }
    }

    /**
     * Capture screen and process with ML Kit OCR or ML Bubble Detection
     */
    private fun captureAndProcessScreen() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()

                bitmap?.let { bmp ->
                    // Use ML bubble detection if enabled and model is ready
                    if (mlDetectionEnabled && mlModelStatus == ModelStatus.READY) {
                        Log.d(TAG, "🔍 Processing screen with ML Bubble Detector...")
                        processImageWithBubbleDetector(bmp)
                    } else if (ocrEnabled) {
                        // Use ML Kit OCR when OCR is enabled
                        Log.d(TAG, "🔍 Processing screen with ML Kit OCR...")
                        processImageWithMLKit(bmp)
                    } else {
                        // Neither detection method is ready/enabled
                        Log.w(TAG, "No detection method available (ML: $mlDetectionEnabled, status: $mlModelStatus, OCR: $ocrEnabled)")
                        bmp.recycle()
                    }
                }
            } else {
                Log.v(TAG, "No image available from ImageReader")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing and processing screen", e)
        }
    }

    /**
     * Process bitmap with ML-based Bubble Detector (ONNX model)
     * This provides more accurate manga-specific text detection.
     */
    private fun processImageWithBubbleDetector(bitmap: Bitmap) {
        coroutineScope.launch {
            try {
                updateOCRStatus(OCRStatus.PROCESSING)
                lastOCRProcessTime = System.currentTimeMillis()
                detectionMethod = "ML Bubble Detector"

                // First, analyze image complexity for multi-signal analysis
                imageComplexity = com.zynt.mangaautoscroller.PanelDetector.calculateImageComplexity(bitmap)

                // Detect panel boundaries if enabled
                if (panelDetectionEnabled) {
                    lastDetectedPanels = com.zynt.mangaautoscroller.PanelDetector.detectPanels(bitmap, screenWidth, screenHeight).toMutableList()
                    adjustScrollingForPanels(lastDetectedPanels)
                }

                // Run bubble detection
                val result = modelManager?.detectBubbles(bitmap)

                if (result != null && result.hasDetections) {
                    ocrSuccessCount++
                    updateOCRStatus(OCRStatus.READY)

                    // Update bubble detection stats
                    lastBubbleCount = result.bubbleCount
                    lastBubbleCoverage = result.totalCoverageArea

                    // Use bubble detection for text density
                    val textDensity = result.textDensityScore * textDetectionSensitivity

                    // Update text density history
                    textDensityHistory.add(textDensity)
                    if (textDensityHistory.size > 5) {
                        textDensityHistory.removeAt(0)
                    }
                    averageTextDensity = textDensityHistory.average().toFloat()
                    currentTextDensity = textDensity

                    // Update display text
                    lastDetectedText = "Detected ${result.bubbleCount} speech bubbles (${(result.totalCoverageArea * 100).toInt()}% coverage)"

                    Log.d(TAG, "Bubble Detection: ${result.bubbleCount} bubbles, density=$textDensity, time=${result.inferenceTimeMs}ms")

                    // Check for dialogue-heavy or action panels
                    if (result.isDialogueHeavy) {
                        Log.i(TAG, "Dialogue-heavy panel detected - using slower scroll")
                    } else if (result.isActionPanel) {
                        Log.i(TAG, "Action panel detected - using faster scroll")
                    }
                } else {
                    // No detections - treat as action panel
                    lastBubbleCount = 0
                    lastBubbleCoverage = 0f
                    currentTextDensity = 0.1f // Low density for action panels
                    lastDetectedText = "No speech bubbles detected"
                    updateOCRStatus(OCRStatus.READY)
                }

                // Clean up bitmap
                bitmap.recycle()

            } catch (e: Exception) {
                ocrFailureCount++
                lastOCRError = e.message
                updateOCRStatus(OCRStatus.ERROR)
                Log.e(TAG, "Error in bubble detection", e)
                bitmap.recycle()

                // Fallback to ML Kit on error
                if (!bitmap.isRecycled) {
                    processImageWithMLKit(bitmap)
                }
            }
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

            // First, analyze image complexity for multi-signal analysis
            imageComplexity = com.zynt.mangaautoscroller.PanelDetector.calculateImageComplexity(bitmap)

            // Detect panel boundaries if enabled
            if (panelDetectionEnabled) {
                lastDetectedPanels = com.zynt.mangaautoscroller.PanelDetector.detectPanels(bitmap, screenWidth, screenHeight).toMutableList()
                // Adjust scrolling based on panel boundaries
                adjustScrollingForPanels(lastDetectedPanels)
            }

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

                    // Save the last detected text for display
                    if (extractedText.isNotEmpty()) {
                        lastDetectedText = extractedText.take(200) + if (extractedText.length > 200) "..." else ""
                    }

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
            Log.d(TAG, "Stopping OCR capture and cleaning up resources...")

            // Cancel all OCR-related coroutine jobs
            ocrCaptureJob?.cancel()

            // Clean up the ImageReader before the VirtualDisplay
            try {
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null
                Log.d(TAG, "ImageReader closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ImageReader", e)
            }

            // Release the virtual display
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                Log.d(TAG, "VirtualDisplay released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing VirtualDisplay", e)
            }

            // Stop the media projection last
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        mediaProjection?.unregisterCallback(mediaProjectionCallback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering callback", e)
                        // Continue with cleanup regardless of this error
                    }
                }
                mediaProjection?.stop()
                mediaProjection = null
                Log.d(TAG, "MediaProjection stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaProjection", e)
            }

            // Reset OCR status and flags if applicable
            updateOCRStatus(OCRStatus.DISABLED)
            ocrEnabled = false

            Log.d(TAG, "OCR capture stopped and resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping OCR capture", e)
            // Even if we hit an error, ensure these are nullified
            virtualDisplay = null
            mediaProjection = null
            imageReader = null
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

            // Release ML model resources
            modelManager?.release()
            modelManager = null
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
     * Calculate adaptive scroll speed based on text density and bubble detection.
     * 
     * Speed logic (delay between scrolls):
     * - More text/bubbles = LONGER delay (slower scrolling, more reading time)
     * - Less text/bubbles = SHORTER delay (faster scrolling through action)
     * 
     * @return Delay in milliseconds between scroll actions
     */
    private fun calculateAdaptiveScrollSpeed(): Long {
        // If ML detection is active and has valid data, use bubble-based calculation
        if (mlDetectionEnabled && mlModelStatus == ModelStatus.READY && lastBubbleCount >= 0) {
            // Calculate based on bubble detection results
            val bubbleFactor = when {
                lastBubbleCount >= 5 -> 2.5f   // Many bubbles: scroll 2.5x slower (more delay)
                lastBubbleCount >= 3 -> 1.8f   // Several bubbles: 1.8x slower
                lastBubbleCount >= 1 -> 1.3f   // Few bubbles: 1.3x slower
                else -> 0.7f                    // No bubbles (action): 0.7x faster (less delay)
            }
            
            // Also factor in bubble coverage area
            val coverageFactor = when {
                lastBubbleCoverage > 0.3f -> 2.0f   // Very text-heavy: much slower
                lastBubbleCoverage > 0.15f -> 1.5f  // Moderate text: slower
                lastBubbleCoverage > 0.05f -> 1.1f  // Some text: slightly slower
                else -> 0.8f                         // Little/no text: faster
            }
            
            // Combine factors (weighted average)
            val combinedFactor = (bubbleFactor * 0.6f + coverageFactor * 0.4f)
            
            // Apply response strength - how much to trust/amplify the detection
            val adjustedFactor = 1f + (combinedFactor - 1f) * adaptiveResponseStrength
            
            val adaptiveSpeed = (baseScrollSpeed * adjustedFactor).toLong()
            
            // Clamp to reasonable bounds
            val minSpeed = 300L  // Minimum delay (fastest scrolling)
            val maxSpeed = 5000L // Maximum delay (slowest scrolling)
            
            return adaptiveSpeed.coerceIn(minSpeed, maxSpeed)
        }
        
        // Fallback: Original text density-based calculation
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

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,  // Removed FLAG_NOT_TOUCHABLE.inv() and FLAG_LAYOUT_NO_LIMITS
                PixelFormat.TRANSLUCENT
            )

            // Important: Set a fixed position for the overlay
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.x = 100  // Set initial X position
            layoutParams.y = 200  // Set initial Y position
            this.layoutParams = layoutParams

            // First add the view with full opacity
            overlayView?.alpha = 1.0f

            // Add view to window manager
            windowManager?.addView(overlayView, layoutParams)

            // Then set the desired opacity after adding
            overlayView?.alpha = overlayOpacity

            // Setup touch listener for manual repositioning
            setupDragToMove()

            // Update UI elements
            updateAllButtons()

            Log.d(TAG, "Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay", e)
            Toast.makeText(this, "Error creating overlay: ${e.message}", Toast.LENGTH_LONG).show()
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
            layoutParams = LinearLayout.LayoutParams(64, 64)
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

        // Add comic type indicator
        val comicTypeIndicator = TextView(this).apply {
            text = getComicTypeEmoji()
            textSize = 14f
            setTextColor(0xFF1C1B1F.toInt())
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tag = "comicTypeIndicator"
        }

        statusLayout.addView(statusDot)
        statusLayout.addView(statusText)
        statusLayout.addView(comicTypeIndicator)

        // Button layout
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            tag = "buttonLayout"
        }

        val playPauseButton = Button(this).apply {
            text = if (isScrolling) "⏸️" else "▶️"
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
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { toggleExpandedView() }
        }

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 14f
            background = createButtonBackground(0xFFB3261E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 8, 0)
            }
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
        expandedLayout.addView(createComicTypeSection())  // Add the new comic type section
        expandedLayout.addView(createScrollDistanceSection())  // Add scroll distance section
        expandedLayout.addView(createReadingSpeedSection())  // Add reading speed section
        expandedLayout.addView(createSpeedControlSection())
        expandedLayout.addView(createDirectionControlSection())
        expandedLayout.addView(createAdaptiveScrollingSection())
        expandedLayout.addView(createAutoPauseSection())  // Add auto-pause section
        expandedLayout.addView(createOCRStatusSection())
        expandedLayout.addView(createOpacityControlSection())

        // Assemble the complete overlay
        mainContainer.addView(headerLayout)
        mainContainer.addView(statusLayout)
        mainContainer.addView(buttonLayout)
        mainContainer.addView(expandedLayout)

        return mainContainer
    }

    private fun createComicTypeSection(): LinearLayout {
        val comicTypeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val comicTypeLabel = TextView(this).apply {
            text = "📚 Comic Type"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val comicTypeButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "comicTypeButtons"
        }

        val comicTypes = listOf("Manga" to "MANGA", "Manhwa" to "MANHWA", "Manhua" to "MANHUA")
        comicTypes.forEach { (label, type) ->
            val comicTypeButton = Button(this).apply {
                text = label
                textSize = 11f
                background = createButtonBackground(
                    if (comicType == type) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
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
                    comicType = type
                    updateComicTypeButtons()
                    saveSettings()
                    Toast.makeText(this@ScrollerOverlayService, "Comic Type: $label", Toast.LENGTH_SHORT).show()
                }
            }
            comicTypeButtonsLayout.addView(comicTypeButton)
        }

        comicTypeLayout.addView(comicTypeLabel)
        comicTypeLayout.addView(comicTypeButtonsLayout)
        return comicTypeLayout
    }

    private fun createScrollDistanceSection(): LinearLayout {
        val distanceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val distanceLabel = TextView(this).apply {
            text = "📏 Scroll Length"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val distanceButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "distanceButtons"
        }

        val distances = listOf("Short" to "SHORT", "Medium" to "MEDIUM", "Long" to "LONG")
        distances.forEach { (label, distance) ->
            val distanceButton = Button(this).apply {
                text = label
                textSize = 11f
                background = createButtonBackground(
                    if (scrollDistance == distance) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
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
                    scrollDistance = distance
                    updateScrollDistanceButtons()
                    applyScrollDistanceToSpeed()
                    saveSettings()
                    Toast.makeText(this@ScrollerOverlayService, "Scroll Distance: $label", Toast.LENGTH_SHORT).show()
                }
            }
            distanceButtonsLayout.addView(distanceButton)
        }

        distanceLayout.addView(distanceLabel)
        distanceLayout.addView(distanceButtonsLayout)
        return distanceLayout
    }

    private fun createReadingSpeedSection(): LinearLayout {
        val speedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val speedLabel = TextView(this).apply {
            text = "⏱️ Reading Speed"
            textSize = 13f
            setTextColor(0xFF49454F.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val speedButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "readingSpeedButtons"
        }

        val speeds = listOf("Slow" to "SLOW", "Normal" to "NORMAL", "Fast" to "FAST")
        speeds.forEach { (label, speed) ->
            val speedButton = Button(this).apply {
                text = label
                textSize = 11f
                background = createButtonBackground(
                    if (readingSpeed == speed) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
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
                    readingSpeed = speed
                    updateReadingSpeedButtons()
                    saveSettings()
                    Toast.makeText(this@ScrollerOverlayService, "Reading Speed: $label", Toast.LENGTH_SHORT).show()
                }
            }
            speedButtonsLayout.addView(speedButton)
        }

        speedLayout.addView(speedLabel)
        speedLayout.addView(speedButtonsLayout)
        return speedLayout
    }

    private fun createAutoPauseSection(): LinearLayout {
        val autoPauseLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val autoPauseLabel = TextView(this).apply {
            text = "⏸️ Auto Pause"
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

        val autoPauseSwitch = Switch(this).apply {
            isChecked = autoPauseEnabled
            setOnCheckedChangeListener { _, isChecked ->
                autoPauseEnabled = isChecked
                saveSettings()
                Toast.makeText(
                    this@ScrollerOverlayService,
                    if (isChecked) "Auto Pause: ON" else "Auto Pause: OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        switchLayout.addView(switchLabel)
        switchLayout.addView(autoPauseSwitch)

        autoPauseLayout.addView(autoPauseLabel)
        autoPauseLayout.addView(switchLayout)

        return autoPauseLayout
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

//        val detectionMethodText = TextView(this).apply {
//            text = "Method: $detectionMethod"
//            textSize = 10f
//            setTextColor(0xFF6B7280.toInt())
//            tag = "detectionMethodText"
//            setPadding(0, 0, 0, 4)
//        }

//        val detectedTextLabel = TextView(this).apply {
//            text = "Last Detected:"
//            textSize = 10f
//            setTextColor(0xFF4B5563.toInt())
//            typeface = android.graphics.Typeface.DEFAULT_BOLD
//            setPadding(0, 4, 0, 2)
//        }

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
//        ocrStatusLayout.addView(detectionMethodText)
//        ocrStatusLayout.addView(detectedTextLabel)
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

    /**
     * Adjust scrolling behavior when approaching panel boundaries
     * @param panels List of panel boundary rectangles
     */
    private fun adjustScrollingForPanels(panels: List<android.graphics.Rect>) {
        // If no panels or feature disabled, exit early
        if (!panelDetectionEnabled || panels.isEmpty() || System.currentTimeMillis() - lastScrollAdjustmentTime < 500) {
            return
        }

        try {
            // Get current scroll position (estimated based on time since scrolling started)
            val currentY = if (scrollStartTime > 0) {
                ((System.currentTimeMillis() - scrollStartTime) * baseScrollSpeed / 1000) % screenHeight
            } else {
                screenHeight / 2 // Default to middle if we don't have a start time
            }

            // Check if we're approaching any panel boundary
            var nearestBoundaryDistance = Int.MAX_VALUE

            for (panel in panels) {
                // Check distance to this panel's bottom
                val distanceToBottom = if (currentDirection == ScrollerAccessibilityService.DIRECTION_DOWN) {
                    panel.bottom - currentY.toInt()
                } else if (currentDirection == ScrollerAccessibilityService.DIRECTION_UP) {
                    currentY.toInt() - panel.top
                } else if (currentDirection == ScrollerAccessibilityService.DIRECTION_RIGHT) {
                    panel.right - currentY.toInt()
                } else {
                    currentY.toInt() - panel.left
                }

                // Only consider panels ahead of our current position within a certain range
                if (distanceToBottom in 1..screenHeight/3) {
                    nearestBoundaryDistance = minOf(nearestBoundaryDistance, distanceToBottom)
                }
            }

            // If we're approaching a boundary, adjust the scroll speed
            if (nearestBoundaryDistance < Int.MAX_VALUE) {
                // Calculate adjustment factor - more slowing as we get closer
                val proximityFactor = 1 - (nearestBoundaryDistance / (screenHeight/3.0f))
                val adjustmentStrength = smartPauseStrength * proximityFactor

                // Adjust scroll speed to slow down
                val adjustedSpeed = (baseScrollSpeed * (1 - (0.8 * adjustmentStrength))).toLong()

                // Apply the adjustment if it's significantly different from current speed
                if (Math.abs(adjustedSpeed - currentScrollSpeed) > baseScrollSpeed * 0.1) {
                    currentScrollSpeed = adjustedSpeed
                    // Record this adjustment for learning
                    recordUserAdjustment("AUTO_PANEL", currentScrollSpeed)
                    lastScrollAdjustmentTime = System.currentTimeMillis()
                    Log.d(TAG, "Panel boundary approaching: slowing to ${currentScrollSpeed}ms (${100 * adjustedSpeed / baseScrollSpeed}%)")
                }

                // If very close to boundary and auto-pause enabled, consider a brief pause
                if (nearestBoundaryDistance < 20 && autoPauseEnabled) {
                    // Brief pause
                    Log.d(TAG, "Panel boundary reached: pausing briefly")
                    temporarilyPauseScrolling(300) // Pause for 300ms
                }
            } else {
                // Reset to base speed if not near any boundary
                if (currentScrollSpeed != baseScrollSpeed) {
                    currentScrollSpeed = baseScrollSpeed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting for panels", e)
        }
    }

    /**
     * Temporarily pause scrolling for a specified duration
     */
    private fun temporarilyPauseScrolling(pauseDuration: Long) {
        val wasScrolling = isScrolling

        if (wasScrolling) {
            stopScrolling()

            // Resume after the specified duration
            Handler(Looper.getMainLooper()).postDelayed({
                if (wasScrolling) {
                    startScrolling()
                }
            }, pauseDuration)
        }
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
            findViewsByTag(container, "speedButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val isSelected = when (button.text) {
                                "0.5x" -> baseScrollSpeed == 4000L
                                "1x" -> baseScrollSpeed == 2000L
                                "2x" -> baseScrollSpeed == 1000L
                                "3x" -> baseScrollSpeed == 666L
                                else -> false
                            }
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateDirectionButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "directionButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val isSelected = when (button.text) {
                                "⬆️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_UP
                                "⬇️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_DOWN
                                "⬅️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_LEFT
                                "➡️" -> currentDirection == ScrollerAccessibilityService.DIRECTION_RIGHT
                                else -> false
                            }
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
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

    /**
     * Update the comic type buttons in the UI
     */
    private fun updateComicTypeButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "comicTypeButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val isSelected = when (button.text) {
                                "Manga" -> comicType == "MANGA"
                                "Manhwa" -> comicType == "MANHWA"
                                "Manhua" -> comicType == "MANHUA"
                                else -> false
                            }
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
                }
            }

            // Update the comic type indicator in the status bar
            findViewsByTag(container, "comicTypeIndicator") { view ->
                if (view is TextView) {
                    view.text = getComicTypeEmoji()
                }
            }
        }
    }

    /**
     * Update the scroll distance buttons in the UI
     */
    private fun updateScrollDistanceButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "distanceButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val isSelected = when (button.text) {
                                "Short" -> scrollDistance == "SHORT"
                                "Medium" -> scrollDistance == "MEDIUM"
                                "Long" -> scrollDistance == "LONG"
                                else -> false
                            }
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Update the reading speed buttons in the UI
     */
    private fun updateReadingSpeedButtons() {
        (overlayView as? LinearLayout)?.let { container ->
            findViewsByTag(container, "readingSpeedButtons") { buttonsLayout ->
                if (buttonsLayout is LinearLayout) {
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            val isSelected = when (button.text) {
                                "Slow" -> readingSpeed == "SLOW"
                                "Normal" -> readingSpeed == "NORMAL"
                                "Fast" -> readingSpeed == "FAST"
                                else -> false
                            }
                            button.background = createButtonBackground(
                                if (isSelected) 0xFF6750A4.toInt() else 0xFF79747E.toInt()
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Update overlay UI with all current settings
     */
    private fun updateAllButtons() {
        updateComicTypeButtons()
        updateScrollDistanceButtons()
        updateReadingSpeedButtons()
        updateSpeedButtons()
        updateDirectionButtons()
        updateOpacityButtons()
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
        scrollStartTime = System.currentTimeMillis()
        updateOverlayUI()

        scrollJob = coroutineScope.launch {
            while (isScrolling && isActive) {
                try {
                    performScrollAction()
                    // Use currentScrollSpeed for delay instead of baseScrollSpeed
                    delay(currentScrollSpeed)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Job was cancelled (user stopped scrolling) - this is expected, not an error
                    Log.d(TAG, "Scroll job cancelled")
                    break
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
                            playPauseButton.text = if (isScrolling) "⏸️" else "▶️"
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

                // Calculate adaptive scroll speed based on text density/bubble detection
                val adjustedSpeed = calculateAdaptiveScrollSpeed()
                
                // IMPORTANT: Actually apply the adjusted speed to the scroll loop delay
                currentScrollSpeed = adjustedSpeed

                // Calculate ADAPTIVE scroll distance based on text density from ML detection
                // More text = shorter scrolls to give reading time
                // Less text (action panels) = longer scrolls to move through faster
                val baseDistance = when(scrollDistance) {
                    "SHORT" -> 200
                    "MEDIUM" -> 400
                    "LONG" -> 600
                    else -> 400
                }
                
                val scrollPixels = calculateAdaptiveScrollDistance(baseDistance)

                // Log adaptive behavior for debugging
                if (mlDetectionEnabled && lastBubbleCount > 0) {
                    Log.d(TAG, "📜 Adaptive scroll: speed=${adjustedSpeed}ms, distance=${scrollPixels}px, bubbles=$lastBubbleCount, density=${"%.2f".format(currentTextDensity)}")
                }

                // Use the new method that incorporates distance
                accessibilityService.performScrollWithDistance(currentDirection, scrollPixels)
            } else {
                // Non-adaptive mode: use base settings
                currentScrollSpeed = baseScrollSpeed
                
                // Calculate scroll distance based on the selected option
                val scrollPixels = when(scrollDistance) {
                    "SHORT" -> 200
                    "MEDIUM" -> 400
                    "LONG" -> 600
                    else -> 400
                }

                // Use the new method that incorporates distance
                accessibilityService.performScrollWithDistance(currentDirection, scrollPixels)
            }
        } else {
            Log.w(TAG, "⚠️ Accessibility service not available")
        }
    }
    
    /**
     * Calculate adaptive scroll distance based on detected text/bubbles.
     * 
     * The idea: 
     * - Text-heavy panels (dialogue): scroll LESS distance, gives more reading time
     * - Action panels (no text): scroll MORE distance, moves through faster
     * - This mimics how a human would naturally read - pausing on text, skimming action
     * 
     * @param baseDistance The user's preferred base scroll distance
     * @return Adjusted scroll distance in pixels
     */
    private fun calculateAdaptiveScrollDistance(baseDistance: Int): Int {
        // If ML detection is providing bubble data, use that
        if (mlDetectionEnabled && mlModelStatus == ModelStatus.READY) {
            // Use bubble coverage to determine scroll behavior
            // High coverage (lots of speech bubbles) = shorter scroll
            // Low coverage (action panels) = longer scroll
            
            val coverageFactor = when {
                lastBubbleCoverage > 0.3f -> 0.5f   // Very text-heavy: scroll 50% of base
                lastBubbleCoverage > 0.15f -> 0.7f  // Moderate text: scroll 70% of base
                lastBubbleCoverage > 0.05f -> 0.9f  // Some text: scroll 90% of base
                else -> 1.2f                         // Action panel: scroll 120% of base
            }
            
            // Also consider bubble count
            val bubbleCountFactor = when {
                lastBubbleCount >= 5 -> 0.6f   // Many bubbles: slow down significantly
                lastBubbleCount >= 3 -> 0.8f   // Several bubbles: slow down moderately
                lastBubbleCount >= 1 -> 0.95f  // Few bubbles: slight slowdown
                else -> 1.1f                    // No bubbles: speed up slightly
            }
            
            // Combine factors with response strength
            val combinedFactor = (coverageFactor * 0.6f + bubbleCountFactor * 0.4f)
            val adjustedFactor = 1f + (combinedFactor - 1f) * adaptiveResponseStrength
            
            val adjustedDistance = (baseDistance * adjustedFactor).toInt()
            
            // Clamp to reasonable bounds
            return adjustedDistance.coerceIn(100, 800)
        }
        
        // Fallback: use text density from basic analysis
        val densityFactor = when {
            currentTextDensity > 0.3f -> 0.6f   // High text density: shorter scroll
            currentTextDensity > 0.2f -> 0.8f   // Medium text density
            currentTextDensity > 0.1f -> 1.0f   // Low text density
            else -> 1.2f                         // Very low: longer scroll
        }
        
        val adjustedFactor = 1f + (densityFactor - 1f) * adaptiveResponseStrength
        return (baseDistance * adjustedFactor).toInt().coerceIn(100, 800)
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

    /**
     * Get the comic type emoji based on the current comic type setting
     */
    private fun getComicTypeEmoji(): String {
        return when (comicType) {
            "MANGA" -> "📚" // Open book emoji for manga
            "MANHWA" -> "📖" // Page with curl emoji for manhwa
            "MANHUA" -> "📘" // Blue book emoji for manhua
            else -> "📚" // Default to manga emoji
        }
    }

    /**
     * Recursively find views with a specific tag in a ViewGroup
     * @param parent The parent ViewGroup to search in
     * @param tag The tag to search for
     * @param action The action to perform on found views
     */
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

    /**
     * Record a user adjustment to scrolling speed for learning purposes
     * @param contentType The type of content (e.g., "DENSE_TEXT", "PANEL_BOUNDARY", etc.)
     * @param adjustedSpeed The speed the user adjusted to
     */
    private fun recordUserAdjustment(contentType: String, adjustedSpeed: Long) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Each content type has its own adjustment history
            val adjustmentKey = "${KEY_USER_ADJUSTMENTS}_${comicType}_$contentType"

            // Get existing adjustments or create new list
            val existingAdjustmentsJson = prefs.getString(adjustmentKey, "[]")
            val gson = com.google.gson.Gson()

            // Create a list to hold our adjustments
            val adjustments = ArrayList<UserAdjustment>()

            // Parse existing adjustments if any
            if (existingAdjustmentsJson != "[]") {
                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(existingAdjustmentsJson).asJsonArray
                    for (element in jsonArray) {
                        val obj = element.asJsonObject
                        val timestamp = obj.get("timestamp").asLong
                        val speed = obj.get("speed").asLong
                        val imgComplexity = obj.get("imageComplexity").asFloat
                        val txtDensity = obj.get("textDensity").asFloat
                        adjustments.add(UserAdjustment(timestamp, speed, imgComplexity, txtDensity))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing adjustments JSON", e)
                    // Continue with empty list if there's a parsing error
                }
            }

            // Add new adjustment with timestamp
            adjustments.add(UserAdjustment(System.currentTimeMillis(), adjustedSpeed, imageComplexity, currentTextDensity))

            // Keep only the most recent 100 adjustments
            while (adjustments.size > 100) {
                adjustments.removeAt(0)
            }

            // Save back to preferences
            val updatedJson = gson.toJson(adjustments)
            prefs.edit().putString(adjustmentKey, updatedJson).apply()

            Log.d(TAG, "Recorded user adjustment for $contentType: $adjustedSpeed ms")

            // Periodically analyze patterns and update behavior model
            if (adjustments.size % 10 == 0) {
                updateScrollBehaviorModel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording user adjustment", e)
        }
    }

    /**
     * Update the scroll behavior model based on learned patterns
     */
    private fun updateScrollBehaviorModel() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // For each content type and comic type, analyze adjustments
            val contentTypes = listOf("DENSE_TEXT", "PANEL_BOUNDARY", "IMAGE_HEAVY", "AUTO_PANEL", "MANUAL")

            for (contentType in contentTypes) {
                val adjustmentKey = "${KEY_USER_ADJUSTMENTS}_${comicType}_$contentType"
                val adjustmentsJson = prefs.getString(adjustmentKey, "[]") ?: "[]"

                // Skip if no data
                if (adjustmentsJson == "[]") continue

                // Parse adjustments
                val adjustments = ArrayList<UserAdjustment>()
                val gson = com.google.gson.Gson()
                val jsonArray = com.google.gson.JsonParser.parseString(adjustmentsJson).asJsonArray

                for (element in jsonArray) {
                    val obj = element.asJsonObject
                    val timestamp = obj.get("timestamp").asLong
                    val speed = obj.get("speed").asLong
                    val imgComplexity = obj.get("imageComplexity").asFloat
                    val txtDensity = obj.get("textDensity").asFloat
                    adjustments.add(UserAdjustment(timestamp, speed, imgComplexity, txtDensity))
                }

                if (adjustments.size < 5) continue // Need more data

                // Calculate average speed for this content type
                var totalSpeed = 0L
                for (adjustment in adjustments) {
                    totalSpeed += adjustment.speed
                }
                val avgSpeed = totalSpeed / adjustments.size

                // Store the learned speed in preferences
                val learnedSpeedKey = "LEARNED_SPEED_${comicType}_$contentType"
                prefs.edit().putLong(learnedSpeedKey, avgSpeed).apply()

                Log.d(TAG, "Updated learned speed for $comicType/$contentType: $avgSpeed ms")
            }

            // Calculate correlation between text density, image complexity and speed
            calculateCorrelations()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating scroll behavior model", e)
        }
    }

    /**
     * Calculate correlations between content characteristics and speed
     */
    private fun calculateCorrelations() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Get all manual adjustments
            val manualAdjustmentKey = "${KEY_USER_ADJUSTMENTS}_${comicType}_MANUAL"
            val adjustmentsJson = prefs.getString(manualAdjustmentKey, "[]") ?: "[]"

            // Skip if no data
            if (adjustmentsJson == "[]") return

            // Parse adjustments
            val adjustments = ArrayList<UserAdjustment>()
            val gson = com.google.gson.Gson()
            val jsonArray = com.google.gson.JsonParser.parseString(adjustmentsJson).asJsonArray

            for (element in jsonArray) {
                val obj = element.asJsonObject
                val timestamp = obj.get("timestamp").asLong
                val speed = obj.get("speed").asLong
                val imgComplexity = obj.get("imageComplexity").asFloat
                val txtDensity = obj.get("textDensity").asFloat
                adjustments.add(UserAdjustment(timestamp, speed, imgComplexity, txtDensity))
            }

            if (adjustments.size < 10) return // Need more data

            // Calculate correlation between text density and speed
            var sumTextDensity = 0.0f
            var sumSpeed = 0L
            var sumTextDensitySquared = 0.0f
            var sumSpeedSquared = 0L
            var sumTextDensitySpeed = 0.0f

            for (adjustment in adjustments) {
                sumTextDensity += adjustment.textDensity
                sumSpeed += adjustment.speed
                sumTextDensitySquared += adjustment.textDensity * adjustment.textDensity
                sumSpeedSquared += adjustment.speed * adjustment.speed
                sumTextDensitySpeed += adjustment.textDensity * adjustment.speed.toFloat()
            }

            val n = adjustments.size

            // Calculate correlation coefficient
            val numeratorText = (n * sumTextDensitySpeed - sumTextDensity * sumSpeed.toFloat())
            val denominatorText1 = (n * sumTextDensitySquared - sumTextDensity * sumTextDensity)
            val denominatorText2 = (n * sumSpeedSquared - sumSpeed * sumSpeed)

            val textDensitySpeedCorrelation = if (denominatorText1 > 0 && denominatorText2 > 0) {
                numeratorText / Math.sqrt((denominatorText1 * denominatorText2).toDouble()).toFloat()
            } else {
                0.0f
            }

            // Save correlation
            prefs.edit().putFloat("TEXT_DENSITY_SPEED_CORRELATION", textDensitySpeedCorrelation).apply()

            // Similar calculation for image complexity
            var sumImageComplexity = 0.0f
            var sumImageComplexitySquared = 0.0f
            var sumImageComplexitySpeed = 0.0f

            for (adjustment in adjustments) {
                sumImageComplexity += adjustment.imageComplexity
                sumImageComplexitySquared += adjustment.imageComplexity * adjustment.imageComplexity
                sumImageComplexitySpeed += adjustment.imageComplexity * adjustment.speed.toFloat()
            }

            // Calculate correlation coefficient
            val numeratorImage = (n * sumImageComplexitySpeed - sumImageComplexity * sumSpeed.toFloat())
            val denominatorImage1 = (n * sumImageComplexitySquared - sumImageComplexity * sumImageComplexity)
            val denominatorImage2 = (n * sumSpeedSquared - sumSpeed * sumSpeed)

            val imageComplexitySpeedCorrelation = if (denominatorImage1 > 0 && denominatorImage2 > 0) {
                numeratorImage / Math.sqrt((denominatorImage1 * denominatorImage2).toDouble()).toFloat()
            } else {
                0.0f
            }

            prefs.edit().putFloat("IMAGE_COMPLEXITY_SPEED_CORRELATION", imageComplexitySpeedCorrelation).apply()

            // Based on correlations, adjust weights for adaptive scrolling
            val textDensityWeight = 0.5f + (Math.abs(textDensitySpeedCorrelation) * 0.5f)
            val imageComplexityWeight = 0.5f + (Math.abs(imageComplexitySpeedCorrelation) * 0.5f)

            // Normalize weights
            val totalWeight = textDensityWeight + imageComplexityWeight
            val normalizedTextWeight = textDensityWeight / totalWeight
            val normalizedImageWeight = imageComplexityWeight / totalWeight

            prefs.edit()
                .putFloat("ADAPTIVE_TEXT_WEIGHT", normalizedTextWeight)
                .putFloat("ADAPTIVE_IMAGE_WEIGHT", normalizedImageWeight)
                .apply()

            Log.d(TAG, "Updated correlation model - Text: $textDensitySpeedCorrelation (weight: $normalizedTextWeight), " +
                    "Image: $imageComplexitySpeedCorrelation (weight: $normalizedImageWeight)")

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating correlations", e)
        }
    }

    /**
     * Get learned speed for current content type
     */
    private fun getLearnedSpeed(contentType: String): Long {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val learnedSpeedKey = "LEARNED_SPEED_${comicType}_$contentType"
        return prefs.getLong(learnedSpeedKey, baseScrollSpeed)
    }

    /**
     * Data class to store user adjustments
     */
    data class UserAdjustment(
        val timestamp: Long,
        val speed: Long,
        val imageComplexity: Float,
        val textDensity: Float
    )

    /**
     * Apply the scroll distance setting to the actual scroll speed
     */
    private fun applyScrollDistanceToSpeed() {
        // Apply scroll distance multiplier to base speed
        val distanceFactor = when(scrollDistance) {
            "SHORT" -> 0.5f
            "MEDIUM" -> 1.0f
            "LONG" -> 2.0f
            else -> 1.0f
        }

        // Update current scroll speed based on base speed and distance factor
        currentScrollSpeed = (baseScrollSpeed * distanceFactor).toLong()

        // If scrolling is active, update the scroll job with new speed
        if (isScrolling) {
            stopScrolling()
            startScrolling()
        }

        Log.d(TAG, "Applied scroll distance: $scrollDistance (factor: $distanceFactor, speed: $currentScrollSpeed)")
    }

    /**
     * Setup touch handling for moving the overlay around the screen
     */
    private fun setupDragToMove() {
        overlayView?.setOnTouchListener { _, event ->
            if (layoutParams == null) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Record initial positions
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Calculate new position
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()

                    // Update the layout
                    layoutParams!!.x = newX
                    layoutParams!!.y = newY
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        Log.d(TAG, "Drag-to-move setup complete")
    }
}
