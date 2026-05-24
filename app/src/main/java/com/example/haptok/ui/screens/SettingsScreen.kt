package com.example.haptok.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.haptok.theme.DarkBackground
import com.example.haptok.theme.DarkSurface
import com.example.haptok.theme.GlassBorder
import com.example.haptok.theme.GlassWhite
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.StatusComplete
import com.example.haptok.theme.StatusError
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary
import com.example.haptok.theme.TextTertiary
import com.example.haptok.ui.components.GlassCard
import kotlin.math.roundToInt

/**
 * Settings screen for configuring the Haptok application.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .safeDrawingPadding()
    ) {
        // ── Top bar ──
        TopAppBar(
            title = { Text("Settings", color = TextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Server URL ──
            SettingsSection(title = "Backend Server", icon = Icons.Filled.Dns) {
                var url by remember { mutableStateOf(uiState.serverUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HaptokViolet,
                        unfocusedBorderColor = GlassBorder,
                        focusedLabelColor = HaptokViolet,
                        unfocusedLabelColor = TextTertiary,
                        cursorColor = HaptokCyan,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextSecondary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_url_field")
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateServerUrl(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = HaptokViolet),
                    modifier = Modifier.testTag("save_url_button"),
                ) {
                    Text("Save")
                }
            }

            // ── Haptic Intensity ──
            SettingsSection(title = "Haptic Intensity", icon = Icons.Filled.Vibration) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = uiState.hapticIntensity,
                        onValueChange = viewModel::updateHapticIntensity,
                        colors = SliderDefaults.colors(
                            thumbColor = HaptokCyan,
                            activeTrackColor = HaptokViolet,
                            inactiveTrackColor = GlassWhite,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("intensity_slider_settings"),
                    )
                    Text(
                        "${(uiState.hapticIntensity * 100).roundToInt()}%",
                        color = HaptokCyan,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            // ── Fallback Mode ──
            SettingsSection(title = "Fallback Mode", icon = Icons.Filled.Hearing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio-to-Haptics Fallback", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Use local audio analysis when server is unavailable",
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = uiState.fallbackEnabled,
                        onCheckedChange = viewModel::toggleFallback,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HaptokCyan,
                            checkedTrackColor = HaptokViolet.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.testTag("fallback_toggle"),
                    )
                }
            }

            // ── Force Native Haptics ──
            SettingsSection(title = "Force Native Haptics", icon = Icons.Filled.Vibration) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Skip Backend Processing", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Always use real-time audio haptics — no upload required. Lower quality but instant.",
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = uiState.forceNativeHaptics,
                        onCheckedChange = viewModel::toggleForceNative,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.testTag("force_native_toggle"),
                    )
                }
            }

            // ── Latency Compensation ──
            SettingsSection(title = "Latency Calibration", icon = Icons.Filled.Speed) {
                Text(
                    "Offset: ${uiState.latencyOffsetMs}ms",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = uiState.latencyOffsetMs.toFloat(),
                    onValueChange = { viewModel.updateLatencyOffset(it.roundToInt()) },
                    valueRange = -50f..50f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = HaptokBlue,
                        activeTrackColor = HaptokBlue,
                        inactiveTrackColor = GlassWhite,
                    ),
                    modifier = Modifier.testTag("latency_slider"),
                )
                Text(
                    "Negative = haptics fire earlier, Positive = later",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // ── Device Capabilities ──
            SettingsSection(title = "Device Capabilities", icon = Icons.Filled.Memory) {
                val profile = uiState.deviceProfile
                if (profile != null) {
                    CapabilityRow("Vibrators", "${profile.vibratorCount}")
                    CapabilityRow("Amplitude Control", if (profile.hasAmplitudeControl) "✓" else "✗")
                    CapabilityRow("HapticGenerator", if (profile.hasHapticGenerator) "✓" else "✗")
                    CapabilityRow("Primitives", profile.supportedPrimitives.joinToString(", ").ifEmpty { "None" })
                    CapabilityRow("API Level", "${profile.androidApiLevel}")
                } else {
                    Text("Loading device info...", color = TextTertiary)
                }
            }

            // ── Cache Management ──
            SettingsSection(title = "Cache", icon = Icons.Filled.Cached) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cached haptic tracks: ${uiState.cacheCount}", color = TextSecondary)
                    Button(
                        onClick = viewModel::clearCache,
                        enabled = !uiState.isCacheClearing && uiState.cacheCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError.copy(alpha = 0.8f)),
                        modifier = Modifier.testTag("clear_cache_button"),
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // ── About ──
            GlassCard {
                Text("Haptok v1.0.0", color = TextTertiary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(12.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = HaptokViolet, modifier = Modifier.size(20.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, color = HaptokCyan, style = MaterialTheme.typography.bodySmall)
    }
}
