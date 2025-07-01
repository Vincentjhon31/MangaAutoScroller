package com.zynt.mangaautoscroller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class ScrollerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScrollerAccessibilityService"
        private var instance: ScrollerAccessibilityService? = null

        // Direction types
        const val DIRECTION_UP = 0
        const val DIRECTION_DOWN = 1
        const val DIRECTION_LEFT = 2
        const val DIRECTION_RIGHT = 3

        // Get service instance
        fun getInstance(): ScrollerAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Manga Auto Scroller accessibility service enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events
    }

    fun performScroll(direction: Int, scrollDuration: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val path = Path()
            var startX = 0f
            var startY = 0f
            var endX = 0f
            var endY = 0f

            // Set up coordinates based on direction
            when (direction) {
                DIRECTION_UP -> {
                    // Swipe from bottom to top
                    startX = screenWidth / 2f
                    startY = screenHeight * 2 / 3f
                    endX = screenWidth / 2f
                    endY = screenHeight / 3f
                }
                DIRECTION_DOWN -> {
                    // Swipe from top to bottom
                    startX = screenWidth / 2f
                    startY = screenHeight / 3f
                    endX = screenWidth / 2f
                    endY = screenHeight * 2 / 3f
                }
                DIRECTION_LEFT -> {
                    // Swipe from right to left
                    startX = screenWidth * 2 / 3f
                    startY = screenHeight / 2f
                    endX = screenWidth / 3f
                    endY = screenHeight / 2f
                }
                DIRECTION_RIGHT -> {
                    // Swipe from left to right
                    startX = screenWidth / 3f
                    startY = screenHeight / 2f
                    endX = screenWidth * 2 / 3f
                    endY = screenHeight / 2f
                }
            }

            // Create the gesture path with smoother curve
            path.moveTo(startX, startY)

            // Use quadratic bezier curve for smoother scrolling
            val controlX = (startX + endX) / 2
            val controlY = (startY + endY) / 2
            path.quadTo(controlX, controlY, endX, endY)

            // Create the gesture description
            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, scrollDuration))
                .build()

            // Execute the gesture
            val result = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Gesture completed with duration: $scrollDuration ms")
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.d(TAG, "Gesture cancelled")
                    callback?.invoke(false)
                }
            }, null)

            Log.d(TAG, "Dispatched gesture, result: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing scroll", e)
            callback?.invoke(false)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }
}
