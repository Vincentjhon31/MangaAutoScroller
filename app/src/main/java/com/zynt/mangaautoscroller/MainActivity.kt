package com.zynt.mangaautoscroller

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zynt.mangaautoscroller.data.model.GitHubRelease
import com.zynt.mangaautoscroller.data.repository.UpdateRepository
import com.zynt.mangaautoscroller.service.ScrollerOverlayService
import com.zynt.mangaautoscroller.ui.SettingsDialog
import com.zynt.mangaautoscroller.ui.components.UpdateAvailableDialog
import com.zynt.mangaautoscroller.ui.theme.MangaAutoScrollerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val TAG = "MangaAutoScroller"

    // MediaProjection for screen capture and OCR
    private var mediaProjectionData: Intent? = null

    // Update checker
    private val updateRepository = UpdateRepository()
    private var availableUpdate by mutableStateOf<GitHubRelease?>(null)
    private var showUpdateDialog by mutableStateOf(false)

    // Settings state with shared preferences backing
    private var scrollSpeed by mutableStateOf(2.0f)
    private var overlayOpacity by mutableStateOf(0.85f)
    private var ocrEnabled by mutableStateOf(true)
    private var adaptiveScrollingEnabled by mutableStateOf(true)
    private var textDetectionSensitivity by mutableStateOf(1.0f)
    private var adaptiveResponseStrength by mutableStateOf(1.0f)
    private var mlDetectionEnabled by mutableStateOf(false) // Use ML bubble detection instead of ML Kit
    private var showSettingsDialog by mutableStateOf(false)

    // Shared preferences constants
    companion object {
        private const val PREFS_NAME = "MangaScrollerPrefs"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_DIRECTION = "scroll_direction"
        private const val KEY_OPACITY = "overlay_opacity"
        private const val KEY_ADAPTIVE_ENABLED = "adaptive_enabled"
        private const val KEY_SENSITIVITY = "text_sensitivity"
        private const val KEY_RESPONSE_STRENGTH = "response_strength"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_ML_DETECTION_ENABLED = "ml_detection_enabled"
        private const val KEY_MEDIA_PROJECTION_DATA = "media_projection_data"
    }

    // Permission launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayPermission()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            mediaProjectionData = result.data
            Log.d(TAG, "✅ OCR permissions granted - using ML Kit text detection")
            Toast.makeText(this, "📱 Smart text detection enabled! Using ML Kit OCR", Toast.LENGTH_LONG).show()
            mediaProjectionEnabled = true
        } else {
            Log.w(TAG, "OCR permission denied - using accessibility text only")
            Toast.makeText(this, "⚠️ Using basic text detection instead", Toast.LENGTH_LONG).show()
            mediaProjectionEnabled = false
        }
    }

    // State variables
    private var overlayPermissionGranted by mutableStateOf(false)
    private var serviceRunning by mutableStateOf(false)
    private var mediaProjectionEnabled by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load settings from SharedPreferences
        loadSettings()

        // Check all permissions on start
        checkAllPermissions()
        // Check service status periodically
        checkServiceStatus()
        
        // Check for app updates
        checkForUpdates()

        setContent {
            MangaAutoScrollerTheme {
                // Update Available Dialog
                if (showUpdateDialog && availableUpdate != null) {
                    UpdateAvailableDialog(
                        release = availableUpdate!!,
                        onDismiss = { showUpdateDialog = false }
                    )
                }
                
                // Settings Dialog
                if (showSettingsDialog) {
                    SettingsDialog(
                        onDismiss = { showSettingsDialog = false },
                        scrollSpeed = scrollSpeed,
                        onScrollSpeedChange = {
                            scrollSpeed = it
                            saveSettings()
                        },
                        ocrEnabled = ocrEnabled,
                        onOcrEnabledChange = {
                            ocrEnabled = it
                            saveSettings()
                        },
                        overlayOpacity = overlayOpacity,
                        onOverlayOpacityChange = {
                            overlayOpacity = it
                            saveSettings()
                        },
                        mlDetectionEnabled = mlDetectionEnabled,
                        onMlDetectionEnabledChange = {
                            mlDetectionEnabled = it
                            saveSettings()
                        },
                        onReset = {
                            resetSettingsToDefaults()
                            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EnhancedMangaScrollerMainScreen(
                        overlayPermissionGranted = overlayPermissionGranted,
                        serviceEnabled = serviceRunning,
                        mediaProjectionEnabled = mediaProjectionEnabled,
                        accessibilityEnabled = accessibilityEnabled,
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestMediaProjection = { requestMediaProjectionPermission() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onToggleService = { enabled ->
                            try {
                                if (enabled) {
                                    startEnhancedOverlayService()
                                } else {
                                    stopOverlayService()
                                }
                                // Delay status check to allow service to start/stop
                                Handler(Looper.getMainLooper()).postDelayed({
                                    checkServiceStatus()
                                }, 1000)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error toggling service", e)
                                Toast.makeText(this@MainActivity, "❌ Service Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                checkServiceStatus()
                            }
                        },
                        onOpenSettings = { showSettingsDialog = true },
                        scrollSpeed = scrollSpeed,
                        onScrollSpeedChange = {
                            scrollSpeed = it
                            saveSettings()
                        },
                        onReset = {
                            resetSettingsToDefaults()
                            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recheck permissions and service status when returning to the app
        checkAllPermissions()
        checkServiceStatus()
    }

    /**
     * Check for app updates from GitHub releases
     */
    private fun checkForUpdates() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val release = updateRepository.checkForUpdates()
                if (release != null) {
                    Log.i(TAG, "📦 Update available: ${release.tagName}")
                    availableUpdate = release
                    showUpdateDialog = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
            }
        }
    }

    private fun checkAllPermissions() {
        checkOverlayPermission()
        checkAccessibilityPermission()
        mediaProjectionEnabled = mediaProjectionData != null
    }

    private fun checkOverlayPermission() {
        overlayPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            0
        }

        if (accessibilityEnabled == 1) {
            val serviceString = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // Log the full service string for debugging
            Log.d(TAG, "Enabled accessibility services: $serviceString")

            // Check multiple possible patterns for the service name
            val possibleServiceNames = listOf(
                "$packageName/.service.ScrollerAccessibilityService",
                "$packageName/com.zynt.mangaautoscroller.service.ScrollerAccessibilityService",
                "com.zynt.mangaautoscroller/.service.ScrollerAccessibilityService",
                "com.zynt.mangaautoscroller/com.zynt.mangaautoscroller.service.ScrollerAccessibilityService"
            )

            val isEnabled = possibleServiceNames.any { serviceName ->
                serviceString.contains(serviceName, ignoreCase = true)
            }

            // Also check if our service name appears anywhere in the string
            val fallbackCheck = serviceString.contains("ScrollerAccessibilityService", ignoreCase = true)

            val finalResult = isEnabled || fallbackCheck

            Log.d(TAG, "Accessibility service check result: $finalResult")
            Log.d(TAG, "Package name: $packageName")

            this.accessibilityEnabled = finalResult
            return finalResult
        }

        this.accessibilityEnabled = false
        return false
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestMediaProjectionPermission() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Please enable 'Manga Auto Scroller' in Accessibility Services", Toast.LENGTH_LONG).show()
    }

    private fun startEnhancedOverlayService() {
        try {
            val intent = Intent(this, ScrollerOverlayService::class.java)

            // Pass settings from MainActivity to service
            val speedMultiplier = scrollSpeed
            // Convert UI speed multiplier to actual delay in milliseconds
            val baseScrollSpeed = (2000L / speedMultiplier).toLong()

            // Add settings as extras to the intent
            intent.putExtra(KEY_SCROLL_SPEED, baseScrollSpeed)
            intent.putExtra(KEY_OPACITY, overlayOpacity)
            intent.putExtra(KEY_ADAPTIVE_ENABLED, adaptiveScrollingEnabled)
            intent.putExtra(KEY_SENSITIVITY, textDetectionSensitivity)
            intent.putExtra(KEY_RESPONSE_STRENGTH, adaptiveResponseStrength)
            intent.putExtra(KEY_OCR_ENABLED, ocrEnabled)
            intent.putExtra(KEY_ML_DETECTION_ENABLED, mlDetectionEnabled)

            // Pass MediaProjection result data to the service if OCR or ML detection is enabled
            // Both OCR (ML Kit) and ML Bubble Detection need screen capture
            val needsScreenCapture = ocrEnabled || mlDetectionEnabled
            if (needsScreenCapture && mediaProjectionData != null) {
                intent.putExtra(KEY_MEDIA_PROJECTION_DATA, mediaProjectionData)
                val mode = when {
                    ocrEnabled && mlDetectionEnabled -> "OCR + ML Detection"
                    mlDetectionEnabled -> "ML Bubble Detection"
                    else -> "OCR"
                }
                Log.d(TAG, "Passing MediaProjection data to service for $mode")
            } else if (needsScreenCapture && mediaProjectionData == null) {
                Log.w(TAG, "⚠️ Screen capture requested but MediaProjection data not available. Please grant screen capture permission.")
                Toast.makeText(this, "⚠️ Please grant screen capture permission for detection to work", Toast.LENGTH_LONG).show()
            }

            // Start the service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            val detectionMode = if (mlDetectionEnabled) "ML Bubble Detection" else "ML Kit OCR"
            Toast.makeText(this, "🚀 Manga Auto Scroller Started! ($detectionMode)", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            Toast.makeText(this, "❌ Failed to start service: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            // Don't rethrow - handle gracefully
        }
    }

    // Remove the problematic SimplifiedOcrService call
    private fun stopOverlayService() {
        try {
            val intent = Intent(this, ScrollerOverlayService::class.java)
            intent.action = "STOP_SERVICE"
            startService(intent)
            Toast.makeText(this, "📱 Manga Auto Scroller Stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop service", e)
            Toast.makeText(this, "❌ Failed to stop service: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkServiceStatus() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)

            serviceRunning = runningServices.any { service ->
                service.service.className == "com.zynt.mangaautoscroller.service.ScrollerOverlayService" &&
                        service.foreground
            }

            Log.d(TAG, "Service running status: $serviceRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status", e)
            serviceRunning = false
        }
    }

    private fun loadSettings() {
        try {
            val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Handle potential type mismatch for scrollSpeed
            try {
                scrollSpeed = sharedPreferences.getFloat(KEY_SCROLL_SPEED, 2.0f)
            } catch (e: ClassCastException) {
                // It might be saved as Long in the service
                Log.w(TAG, "Converting scroll speed from Long to Float")
                val longValue = sharedPreferences.getLong(KEY_SCROLL_SPEED, 2000L)
                scrollSpeed = (longValue / 1000f)
                // Save it back as Float to prevent future errors
                sharedPreferences.edit().putFloat(KEY_SCROLL_SPEED, scrollSpeed).apply()
            }

            // Load other settings normally
            overlayOpacity = sharedPreferences.getFloat(KEY_OPACITY, 0.85f)
            ocrEnabled = sharedPreferences.getBoolean(KEY_OCR_ENABLED, true)
            adaptiveScrollingEnabled = sharedPreferences.getBoolean(KEY_ADAPTIVE_ENABLED, true)
            textDetectionSensitivity = sharedPreferences.getFloat(KEY_SENSITIVITY, 1.0f)
            adaptiveResponseStrength = sharedPreferences.getFloat(KEY_RESPONSE_STRENGTH, 1.0f)
            mlDetectionEnabled = sharedPreferences.getBoolean(KEY_ML_DETECTION_ENABLED, false)

            Log.d(TAG, "Settings loaded: speed=$scrollSpeed, opacity=$overlayOpacity, mlDetection=$mlDetectionEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings", e)
            // If loading fails, we'll use the default values
        }
    }

    private fun saveSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putFloat(KEY_SCROLL_SPEED, scrollSpeed)
            putFloat(KEY_OPACITY, overlayOpacity)
            putBoolean(KEY_OCR_ENABLED, ocrEnabled)
            putBoolean(KEY_ADAPTIVE_ENABLED, adaptiveScrollingEnabled)
            putFloat(KEY_SENSITIVITY, textDetectionSensitivity)
            putFloat(KEY_RESPONSE_STRENGTH, adaptiveResponseStrength)
            putBoolean(KEY_ML_DETECTION_ENABLED, mlDetectionEnabled)
            apply()
        }
    }

    private fun resetSettingsToDefaults() {
        scrollSpeed = 2.0f
        overlayOpacity = 0.85f
        ocrEnabled = true
        adaptiveScrollingEnabled = true
        textDetectionSensitivity = 1.0f
        adaptiveResponseStrength = 1.0f
        mlDetectionEnabled = false
        saveSettings()
    }
}

@Composable
fun EnhancedMangaScrollerMainScreen(
    overlayPermissionGranted: Boolean,
    serviceEnabled: Boolean,
    mediaProjectionEnabled: Boolean,
    accessibilityEnabled: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    scrollSpeed: Float = 2.0f,
    onScrollSpeedChange: (Float) -> Unit = {},
    onReset: () -> Unit = {}
) {
    val context = LocalContext.current
    var showInstructions by remember { mutableStateOf(false) }

    // Calculate setup progress
    val setupProgress = listOf(
        overlayPermissionGranted,
        accessibilityEnabled,
        mediaProjectionEnabled
    ).count { it } / 3f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Modern App Header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Manga Auto Scroller",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Smart adaptive scrolling for manga",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Setup Progress Indicator
        if (setupProgress < 1f) {
            SetupProgressCard(
                progress = setupProgress,
                completedSteps = (setupProgress * 3).toInt(),
                totalSteps = 3
            )
        }

        // Permission Cards with improved design
        AnimatedVisibility(visible = !overlayPermissionGranted) {
            PermissionCard(
                title = "Display Over Apps",
                description = "Required to show floating controls over your manga app",
                icon = Icons.Default.OpenInBrowser,
                buttonText = "Grant Permission",
                onButtonClick = onRequestOverlayPermission,
                isRequired = true
            )
        }

        AnimatedVisibility(visible = !accessibilityEnabled) {
            PermissionCard(
                title = "Accessibility Service",
                description = "Enables automatic scrolling gestures in any app",
                icon = Icons.Default.Accessibility,
                buttonText = "Enable Service",
                onButtonClick = onOpenAccessibilitySettings,
                isRequired = true
            )
        }

        AnimatedVisibility(visible = !mediaProjectionEnabled) {
            PermissionCard(
                title = "Smart Text Detection",
                description = "Advanced OCR for reading text in manga images",
                icon = Icons.Default.Visibility,
                buttonText = "Enable OCR",
                onButtonClick = onRequestMediaProjection,
                isRequired = false
            )
        }

        // Main Control Card
        MainControlCard(
            serviceEnabled = serviceEnabled,
            allPermissionsGranted = overlayPermissionGranted && accessibilityEnabled,
            mediaProjectionEnabled = mediaProjectionEnabled,
            onToggleService = onToggleService
        )

        // Quick Actions - Fixed version with proper variable references
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick speed adjustment
                Text(
                    text = "Scroll Speed: ${String.format("%.1f", scrollSpeed)}x",
                    style = MaterialTheme.typography.bodySmall
                )

                Slider(
                    value = scrollSpeed,
                    onValueChange = onScrollSpeedChange,
                    valueRange = 0.5f..5f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showInstructions = !showInstructions },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (showInstructions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (showInstructions) "Hide Guide" else "Show Guide")
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Settings")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Reset button
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset to Defaults")
                }
            }
        }

        // Instructions (collapsible)
        AnimatedVisibility(visible = showInstructions) {
            InstructionsCard()
        }

        // Status Footer
        StatusFooter(
            overlayPermissionGranted = overlayPermissionGranted,
            accessibilityEnabled = accessibilityEnabled,
            mediaProjectionEnabled = mediaProjectionEnabled,
            serviceEnabled = serviceEnabled
        )
    }
}

@Composable
fun SetupProgressCard(
    progress: Float,
    completedSteps: Int,
    totalSteps: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Setup Progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$completedSteps/$totalSteps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (completedSteps) {
                    0 -> "Let's get started with the setup"
                    1 -> "Great! Continue with the remaining steps"
                    2 -> "Almost done! One more step"
                    else -> "Perfect! You're all set"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonText: String,
    onButtonClick: () -> Unit,
    isRequired: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRequired)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isRequired)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRequired)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (isRequired) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRequired)
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onButtonClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRequired)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
fun MainControlCard(
    serviceEnabled: Boolean,
    allPermissionsGranted: Boolean,
    mediaProjectionEnabled: Boolean,
    onToggleService: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (serviceEnabled) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (serviceEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Auto Scroller",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (serviceEnabled) "Active" else "Inactive",
                style = MaterialTheme.typography.bodyMedium,
                color = if (serviceEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Feature status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FeatureStatusIndicator(
                    label = "Basic",
                    enabled = allPermissionsGranted,
                    icon = Icons.Default.TouchApp
                )
                FeatureStatusIndicator(
                    label = "Smart OCR",
                    enabled = mediaProjectionEnabled,
                    icon = Icons.Default.Visibility
                )
                FeatureStatusIndicator(
                    label = "Adaptive",
                    enabled = serviceEnabled,
                    icon = Icons.Default.AutoAwesome
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main toggle button
            Button(
                onClick = { onToggleService(!serviceEnabled) },
                enabled = allPermissionsGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceEnabled)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (serviceEnabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (serviceEnabled) "Stop Service" else "Start Service",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (!allPermissionsGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Complete setup steps above to enable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FeatureStatusIndicator(
    label: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun InstructionsCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "How to Use",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val instructions = listOf(
                "Complete all setup steps above" to "Ensure all permissions are granted",
                "Start the Auto Scroller service" to "Use the big button to activate",
                "Open your manga app" to "Navigate to your favorite manga reader",
                "Use floating controls" to "Tap play/pause to control scrolling",
                "Adjust settings" to "Tap the gear icon to customize speed and sensitivity",
                "Reposition controls" to "Drag the top bar to move the overlay"
            )

            instructions.forEachIndexed { index, (title, description) ->
                InstructionStep(
                    stepNumber = index + 1,
                    title = title,
                    description = description
                )
                if (index < instructions.size - 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun InstructionStep(
    stepNumber: Int,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun StatusFooter(
    overlayPermissionGranted: Boolean,
    accessibilityEnabled: Boolean,
    mediaProjectionEnabled: Boolean,
    serviceEnabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem("Overlay", overlayPermissionGranted)
                StatusItem("Accessibility", accessibilityEnabled)
                StatusItem("OCR", mediaProjectionEnabled)
                StatusItem("Service", serviceEnabled)
            }
        }
    }
}

@Composable
fun StatusItem(
    label: String,
    enabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) Color(0xFF4CAF50) else Color(0xFFFF5722)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
