package com.zynt.mangaautoscroller.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    scrollSpeed: Float,
    onScrollSpeedChange: (Float) -> Unit,
    ocrEnabled: Boolean,
    onOcrEnabledChange: (Boolean) -> Unit,
    overlayOpacity: Float,
    onOverlayOpacityChange: (Float) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Scroll Speed Section
                Text(
                    text = "Scroll Speed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Adjust how fast the auto-scroller moves",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = scrollSpeed,
                    onValueChange = onScrollSpeedChange,
                    valueRange = 0.5f..5f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Slow", style = MaterialTheme.typography.bodySmall)
                    Text("${String.format("%.1f", scrollSpeed)}x", style = MaterialTheme.typography.bodySmall)
                    Text("Fast", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // OCR Detection Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart OCR Detection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Uses text detection to adjust scroll speed automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = ocrEnabled,
                        onCheckedChange = onOcrEnabledChange
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Overlay Opacity Section
                Text(
                    text = "Overlay Transparency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Control how transparent the floating controls appear",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = overlayOpacity,
                    onValueChange = onOverlayOpacityChange,
                    valueRange = 0.2f..1f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Transparent", style = MaterialTheme.typography.bodySmall)
                    Text("${(overlayOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Text("Opaque", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Settings are automatically saved via state
                            onDismiss()
                        }
                    ) {
                        Text("Save Settings")
                    }
                }
            }
        }
    }
}
