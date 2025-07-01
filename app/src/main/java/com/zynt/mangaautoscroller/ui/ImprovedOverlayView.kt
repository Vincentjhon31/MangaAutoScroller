package com.zynt.mangaautoscroller.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.*
import android.widget.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zynt.mangaautoscroller.ui.theme.MangaAutoScrollerTheme

class ImprovedOverlayView(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var isScrolling by mutableStateOf(false)
    private var scrollSpeed by mutableStateOf(2.0f)
    private var showSettings by mutableStateOf(false)
    private var isMinimized by mutableStateOf(false)

    // Callbacks
    var onPlayPauseClick: ((Boolean) -> Unit)? = null
    var onSpeedChange: ((Float) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = ComposeView(context).apply {
            setContent {
                MangaAutoScrollerTheme {
                    OverlayContent(
                        isScrolling = isScrolling,
                        scrollSpeed = scrollSpeed,
                        showSettings = showSettings,
                        isMinimized = isMinimized,
                        onPlayPauseClick = { playing ->
                            isScrolling = playing
                            onPlayPauseClick?.invoke(playing)
                        },
                        onSpeedChange = { speed ->
                            scrollSpeed = speed
                            onSpeedChange?.invoke(speed)
                        },
                        onSettingsToggle = { showSettings = !showSettings },
                        onMinimizeToggle = { isMinimized = !isMinimized },
                        onClose = {
                            hide()
                            onClose?.invoke()
                        }
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
        windowManager = null
    }

    fun updateScrollingState(isScrolling: Boolean) {
        this.isScrolling = isScrolling
    }
}

@Composable
private fun OverlayContent(
    isScrolling: Boolean,
    scrollSpeed: Float,
    showSettings: Boolean,
    isMinimized: Boolean,
    onPlayPauseClick: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSettingsToggle: () -> Unit,
    onMinimizeToggle: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        if (isMinimized) {
            // Minimized view - just a floating button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onMinimizeToggle,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (isScrolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        } else {
            // Full overlay view
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with title and controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manga Scroller",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row {
                        IconButton(
                            onClick = onMinimizeToggle,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Minimize,
                                contentDescription = "Minimize",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isScrolling) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScrolling) "Scrolling Active" else "Paused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isScrolling) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Main control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Play/Pause button
                    FloatingActionButton(
                        onClick = { onPlayPauseClick(!isScrolling) },
                        modifier = Modifier.size(56.dp),
                        containerColor = if (isScrolling)
                            MaterialTheme.colorScheme.error else
                            MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = if (isScrolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isScrolling) "Pause" else "Play",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Settings button
                    FloatingActionButton(
                        onClick = onSettingsToggle,
                        modifier = Modifier.size(56.dp),
                        containerColor = MaterialTheme.colorScheme.secondary
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Settings panel
                if (showSettings) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Speed Control",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Slider(
                                value = scrollSpeed,
                                onValueChange = onSpeedChange,
                                valueRange = 0.5f..5f,
                                steps = 18,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Slow",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${String.format("%.1f", scrollSpeed)}x",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Fast",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick speed buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = { onSpeedChange(1f) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1x", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { onSpeedChange(2f) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2x", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { onSpeedChange(3f) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("3x", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
