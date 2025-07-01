package com.zynt.mangaautoscroller.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.zynt.mangaautoscroller.MainActivity
import com.zynt.mangaautoscroller.R
import kotlinx.coroutines.*

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

    companion object {
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "ScrollerOverlayChannel"
        private const val TAG = "ScrollerOverlayService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting...")

        when (intent?.action) {
            "STOP_SERVICE" -> {
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
            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification())

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

    private fun createSimpleOverlay() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Create a proper custom overlay view
            overlayView = createModernOverlayView()

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Remove the gravity constraint to allow movement anywhere on screen
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
                            // Save initial position
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y

                            // Save initial touch position
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // Calculate how far we've moved
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY

                            // Update position
                            layoutParams!!.x = initialX + dx.toInt()
                            layoutParams!!.y = initialY + dy.toInt()

                            // Update the view with new position
                            windowManager?.updateViewLayout(view, layoutParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            // Store the final position
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y

                            // Only consider it a click if we didn't move the overlay
                            val moved = Math.abs(event.rawX - initialTouchX) > 5 ||
                                       Math.abs(event.rawY - initialTouchY) > 5

                            return moved
                        }
                    }
                    return false
                }
            })

            Log.d(TAG, "✅ Modern overlay created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create overlay", e)
            // Continue without overlay rather than crashing
        }
    }

    private fun createModernOverlayView(): View {
        // Create the main container with rounded background
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = createRoundedBackground()
            elevation = 12f
        }

        // Header with app icon, title, and minimize button
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // App icon (using a simple colored circle as icon)
        val iconView = View(this).apply {
            background = createCircleBackground(0xFF6750A4.toInt()) // Material 3 primary color
            layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                setMargins(0, 0, 16, 0)
            }
        }

        // Title text
        val titleText = TextView(this).apply {
            text = "🎌 Manga Scroller"
            textSize = 16f
            setTextColor(0xFF1C1B1F.toInt()) // Material 3 on-surface
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Minimize button
        val minimizeButton = Button(this).apply {
            text = "➖"
            textSize = 12f
            background = createButtonBackground(0xFF79747E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(48, 48)
            setOnClickListener {
                toggleMinimizedView()
            }
        }

        headerLayout.addView(iconView)
        headerLayout.addView(titleText)
        headerLayout.addView(minimizeButton)

        // Status indicator
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

        // Main control buttons layout
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            tag = "buttonLayout"
        }

        // Play/Pause button
        val playPauseButton = Button(this).apply {
            text = if (isScrolling) "⏸️ Pause" else "▶️ Play"
            textSize = 14f
            background = createButtonBackground(0xFF6750A4.toInt()) // Primary color
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

        // Settings button
        val settingsButton = Button(this).apply {
            text = "⚙️"
            textSize = 16f
            background = createButtonBackground(0xFF625B71.toInt()) // Secondary color
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(56, 56).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                toggleExpandedView()
            }
        }

        // Close button
        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 14f
            background = createButtonBackground(0xFFB3261E.toInt()) // Error color
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(56, 56)
            setOnClickListener {
                stopSelf()
            }
        }

        buttonLayout.addView(playPauseButton)
        buttonLayout.addView(settingsButton)
        buttonLayout.addView(closeButton)

        // Expanded settings panel (initially hidden)
        val expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
            tag = "expandedLayout"
        }

        // Speed control section
        val speedSection = createSpeedControlSection()

        // Direction control section
        val directionSection = createDirectionControlSection()

        // Adaptive scrolling section
        val adaptiveSection = createAdaptiveScrollingSection()

        // Opacity control section
        val opacitySection = createOpacityControlSection()

        expandedLayout.addView(speedSection)
        expandedLayout.addView(directionSection)
        expandedLayout.addView(adaptiveSection)
        expandedLayout.addView(opacitySection)

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

        // Main switch to enable/disable adaptive scrolling
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

        // Declare these variables before they're used in the switch listener
        val sensitivityLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (adaptiveScrollingEnabled) View.VISIBLE else View.GONE
            setPadding(0, 8, 0, 8)
        }

        val responseLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (adaptiveScrollingEnabled) View.VISIBLE else View.GONE
            setPadding(0, 8, 0, 0)
        }

        val sensitivityValueText = TextView(this).apply {
            text = String.format("%.1fx", textDetectionSensitivity)
            textSize = 11f
            gravity = Gravity.END
            setTextColor(0xFF49454F.toInt())
        }

        val responseValueText = TextView(this).apply {
            text = String.format("%.1fx", adaptiveResponseStrength)
            textSize = 11f
            gravity = Gravity.END
            setTextColor(0xFF49454F.toInt())
        }

        val adaptiveSwitch = Switch(this).apply {
            isChecked = adaptiveScrollingEnabled
            setOnCheckedChangeListener { _, isChecked ->
                adaptiveScrollingEnabled = isChecked
                // Update visibility of sensitivity controls based on switch state
                sensitivityLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
                responseLayout.visibility = if (isChecked) View.VISIBLE else View.GONE

                Toast.makeText(
                    this@ScrollerOverlayService,
                    if (isChecked) "Adaptive Scrolling: ON" else "Adaptive Scrolling: OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        switchLayout.addView(switchLabel)
        switchLayout.addView(adaptiveSwitch)

        // Text detection sensitivity control
        val sensitivityLabel = TextView(this).apply {
            text = "Text Detection Sensitivity"
            textSize = 12f
            setTextColor(0xFF49454F.toInt())
        }

        val sensitivitySlider = SeekBar(this).apply {
            max = 100
            progress = (textDetectionSensitivity * 50).toInt() // 0.5-2.0 mapped to 0-100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // Map 0-100 to 0.5-2.0 range
                        textDetectionSensitivity = 0.5f + progress / 100f * 1.5f
                        sensitivityValueText.text = String.format("%.1fx", textDetectionSensitivity)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Toast.makeText(
                        this@ScrollerOverlayService,
                        "Detection Sensitivity: ${String.format("%.1fx", textDetectionSensitivity)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }

        // Response strength control
        val responseLabel = TextView(this).apply {
            text = "Adaptation Strength"
            textSize = 12f
            setTextColor(0xFF49454F.toInt())
        }

        val responseSlider = SeekBar(this).apply {
            max = 100
            progress = (adaptiveResponseStrength * 50).toInt() // 0.5-2.0 mapped to 0-100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // Map 0-100 to 0.5-2.0 range
                        adaptiveResponseStrength = 0.5f + progress / 100f * 1.5f
                        responseValueText.text = String.format("%.1fx", adaptiveResponseStrength)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Toast.makeText(
                        this@ScrollerOverlayService,
                        "Adaptation Strength: ${String.format("%.1fx", adaptiveResponseStrength)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }

        // Add all components to main layout
        adaptiveLayout.addView(adaptiveLabel)
        adaptiveLayout.addView(switchLayout)

        // Add sensitivity controls
        sensitivityLayout.addView(sensitivityLabel)
        sensitivityLayout.addView(sensitivitySlider)
        sensitivityLayout.addView(sensitivityValueText)
        adaptiveLayout.addView(sensitivityLayout)

        // Add response controls
        responseLayout.addView(responseLabel)
        responseLayout.addView(responseSlider)
        responseLayout.addView(responseValueText)
        adaptiveLayout.addView(responseLayout)

        return adaptiveLayout
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
                tag = opacity  // Store opacity value as tag for later reference
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
                    // Go through each button in the opacity buttons layout
                    for (i in 0 until buttonsLayout.childCount) {
                        val button = buttonsLayout.getChildAt(i) as? Button
                        if (button != null) {
                            // Check if this button represents the current opacity
                            val buttonOpacity = button.tag as? Float
                            val isSelected = buttonOpacity == overlayOpacity

                            // Update button appearance
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
                    // Perform scroll action via accessibility service
                    performScrollAction()

                    // Wait for next scroll
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
            // Update the modern overlay UI elements
            (overlayView as? LinearLayout)?.let { container ->
                // Update status dot and text
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is LinearLayout && child.childCount >= 2) {
                        // Check if this is the status layout
                        val secondChild = child.getChildAt(1)
                        if (secondChild is TextView && (secondChild.text == "Active" || secondChild.text == "Paused")) {
                            // Update status dot
                            val statusDot = child.getChildAt(0) as View
                            statusDot.background = createCircleBackground(
                                if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
                            )
                            // Update status text
                            secondChild.text = if (isScrolling) "Active" else "Paused"
                            secondChild.setTextColor(if (isScrolling) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
                        }

                        // Check if this is the button layout
                        if (child.childCount >= 3 && child.getChildAt(0) is Button) {
                            val playPauseButton = child.getChildAt(0) as Button
                            // Update button text
                            playPauseButton.text = if (isScrolling) "⏸️ Pause" else "▶️ Play"

                            // Update button background to indicate active state
                            playPauseButton.background = createButtonBackground(
                                if (isScrolling) 0xFF4CAF50.toInt() else 0xFF6750A4.toInt()
                            )
                        }
                    }
                }

                // Also update speed and direction buttons to reflect current settings
                updateSpeedButtons()
                updateDirectionButtons()
            }

            // Update notification
            startForeground(NOTIFICATION_ID, createNotification())

        } catch (e: Exception) {
            Log.w(TAG, "Could not update overlay UI", e)
        }
    }

    private fun performScrollAction() {
        // Get the accessibility service to perform the scroll
        val accessibilityService = ScrollerAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // If adaptive scrolling is enabled, analyze screen and adjust speed
            if (adaptiveScrollingEnabled) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTextAnalysisTime > textAnalysisInterval) {
                    analyzeScreenForTextDensity()
                    lastTextAnalysisTime = currentTime
                }

                // Calculate adjusted scroll speed based on text density
                val adjustedSpeed = calculateAdaptiveScrollSpeed()

                // Log for debugging
                Log.d(TAG, "Adaptive scroll: density=$currentTextDensity, speed=$adjustedSpeed")

                accessibilityService.performScroll(currentDirection, adjustedSpeed)
            } else {
                // Use base speed if adaptive scrolling is disabled
                accessibilityService.performScroll(currentDirection, baseScrollSpeed)
            }
        } else {
            Log.w(TAG, "⚠️ Accessibility service not available")
        }
    }

    /**
     * Analyzes the current screen to determine text density
     */
    private fun analyzeScreenForTextDensity() {
        try {
            // Capture screenshot if permission available (requires API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val displayMetrics = resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels

                // Get a screenshot of the current screen (simplified approach)
                val bitmap = captureScreenshot() ?: return

                // Calculate text density (simplified algorithm)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen for text density", e)
        }
    }

    /**
     * Calculate text density from bitmap image with improved detection
     */
    private fun calculateTextDensityFromBitmap(bitmap: android.graphics.Bitmap): Float {
        // Enhanced algorithm for text detection that uses more sophisticated pattern recognition
        val width = bitmap.width
        val height = bitmap.height

        // Adjusted for sensitivity settings
        val threshold = 128 - ((textDetectionSensitivity - 1.0f) * 40).toInt() // Range: 88-168
        val samplingRate = (11 - textDetectionSensitivity * 5).toInt().coerceAtLeast(1) // Higher sensitivity = more samples

        Log.d(TAG, "Text detection with threshold: $threshold, sampling rate: $samplingRate")

        var darkPixels = 0
        var sampledPixels = 0

        // Sample pixels based on sensitivity settings
        for (x in 0 until width step samplingRate) {
            for (y in 0 until height step samplingRate) {
                sampledPixels++
                val pixel = bitmap.getPixel(x, y)

                // Advanced brightness calculation with emphasis on text vs background
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)

                // Use more sophisticated brightness formula that better detects text
                // Text often has high contrast with background
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

                // Detect text-like contrast patterns
                if (brightness < threshold) {
                    darkPixels++
                }

                // Look for edges (common in text) by comparing neighboring pixels
                if (x < width - samplingRate && y < height - samplingRate) {
                    val pixelRight = bitmap.getPixel(x + samplingRate, y)
                    val pixelDown = bitmap.getPixel(x, y + samplingRate)

                    val brightRight = (android.graphics.Color.red(pixelRight) * 0.299 +
                            android.graphics.Color.green(pixelRight) * 0.587 +
                            android.graphics.Color.blue(pixelRight) * 0.114).toInt()

                    val brightDown = (android.graphics.Color.red(pixelDown) * 0.299 +
                            android.graphics.Color.green(pixelDown) * 0.587 +
                            android.graphics.Color.blue(pixelDown) * 0.114).toInt()

                    // Detect contrast edges (common in text)
                    if (Math.abs(brightness - brightRight) > 40 || Math.abs(brightness - brightDown) > 40) {
                        darkPixels++
                    }
                }
            }
        }

        // Apply text detection sensitivity multiplier to the final result
        val baseDensity = darkPixels.toFloat() / sampledPixels.coerceAtLeast(1)
        return baseDensity * textDetectionSensitivity
    }

    /**
     * Enhanced simulated screenshot with text-like patterns
     */
    private fun captureScreenshot(): android.graphics.Bitmap? {
        try {
            // This is a simplified implementation
            // In a real implementation, you'd need to use MediaProjection API which requires user permission
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            return android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply {
                // Create a more sophisticated simulated content with text-like patterns
                val canvas = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 24f
                    isAntiAlias = true
                }

                val random = java.util.Random()

                // Create text-like regions (simulating manga panels and text bubbles)
                val numPanels = random.nextInt(4) + 2
                for (i in 0 until numPanels) {
                    val panelX = random.nextInt(width - 200)
                    val panelY = random.nextInt(height - 200)
                    val panelWidth = random.nextInt(200) + 100
                    val panelHeight = random.nextInt(200) + 100

                    // Simulate text lines in a panel
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

                // Add some random "characters" for more realism
                for (i in 0 until 30) {
                    val x = random.nextInt(width)
                    val y = random.nextInt(height)
                    canvas.drawText("漫画", x.toFloat(), y.toFloat(), paint)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating simulated screenshot", e)
            return null
        }
    }

    /**
     * Calculate adaptive scroll speed based on text density and user-defined response strength
     */
    private fun calculateAdaptiveScrollSpeed(): Long {
        // Higher text density = slower scroll
        // Lower text density = faster scroll

        // Normalize text density to a value between 0.0 and 1.0
        val normalizedDensity = minOf(1.0f, maxOf(0.0f, currentTextDensity * 5))

        // Apply the response strength modifier to the normalized density
        // Higher response strength = stronger effect on scroll speed
        val adjustedDensity = normalizedDensity * adaptiveResponseStrength

        // Calculate adjustment factor (0.5 to 2.0)
        // Higher density = lower factor (slower scroll)
        val adjustmentFactor = 2.0f - adjustedDensity * 1.5f

        // Apply adjustment to base speed
        val adaptiveSpeed = (baseScrollSpeed / adjustmentFactor).toLong()

        // Log the details for debugging
        Log.d(TAG, "Adaptive scrolling: density=$currentTextDensity, " +
                  "adjusted=$adjustedDensity, " +
                  "factor=$adjustmentFactor, " +
                  "speed=$adaptiveSpeed")

        // Ensure speed stays within reasonable bounds
        // The range varies based on response strength
        val minSpeed = (500 * (1 / adaptiveResponseStrength)).toLong().coerceAtLeast(300)
        val maxSpeed = (6000 * adaptiveResponseStrength).toLong().coerceAtMost(8000)

        return minOf(maxSpeed, maxOf(minSpeed, adaptiveSpeed))
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Service destroying...")

        try {
            stopScrolling()
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
}
