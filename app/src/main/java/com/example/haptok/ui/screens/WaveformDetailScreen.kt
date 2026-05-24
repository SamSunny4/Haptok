package com.example.haptok.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.haptok.theme.DarkBackground
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokCyan
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary
import com.example.haptok.theme.TextTertiary
import com.example.haptok.ui.components.GlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WaveformDetailScreen(
    cacheId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WaveformDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(cacheId) {
        viewModel.loadDetail(cacheId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Waveform Details",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            
            if (uiState.track != null) {
                IconButton(onClick = { viewModel.exportWaveform(cacheId) }) {
                    Icon(Icons.Default.Download, contentDescription = "Export", tint = HaptokCyan)
                }
                IconButton(onClick = { viewModel.deleteWaveform(cacheId) { onBack() } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaptokViolet)
                }
            }
            uiState.errorMessage != null || uiState.track == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            uiState.errorMessage ?: "Waveform not found",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
            else -> {
                val track = uiState.track!!
                val timeline = uiState.timeline
                val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm:ss", Locale.getDefault())
                val dateString = dateFormat.format(Date(track.createdAt))
                val durationSec = track.videoDurationSeconds.toInt()
                val min = durationSec / 60
                val sec = durationSec % 60
                
                val impactCount = timeline?.tracks?.find { it.name == "impacts" }?.events?.size ?: 0
                val textureCount = timeline?.tracks?.find { it.name == "texture" }?.events?.size ?: 0
                val ambientCount = timeline?.tracks?.find { it.name == "ambient" }?.events?.size ?: 0
                val motionCount = timeline?.tracks?.find { it.name == "motion" }?.events?.size ?: 0
                val totalCount = impactCount + textureCount + ambientCount + motionCount

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Header Card
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        showAccentEdge = true,
                        accentColor = HaptokViolet
                    ) {
                        Column {
                            Text(
                                text = track.videoTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Duration: $min:${sec.toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "Generated: $dateString",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    // Track Breakdown
                    Text(
                        text = "Track Breakdown ($totalCount events)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EventStatCard(title = "Impacts", count = impactCount, color = Color(0xFFFF5252), modifier = Modifier.weight(1f))
                        EventStatCard(title = "Textures", count = textureCount, color = Color(0xFF69F0AE), modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EventStatCard(title = "Ambient", count = ambientCount, color = Color(0xFF448AFF), modifier = Modifier.weight(1f))
                        EventStatCard(title = "Motion", count = motionCount, color = Color(0xFFFFD740), modifier = Modifier.weight(1f))
                    }

                    // Metadata
                    if (timeline?.metadata != null) {
                        Text(
                            text = "Generation Metadata",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val meta = timeline.metadata
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = HaptokBlue, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Timeline Version: ${timeline.version}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                Text("Models: ${meta.modelsUsed.joinToString()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text("Generated: ${meta.generationTimestamp}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                
                                val avgConfidence = meta.confidenceScores?.values?.average()
                                if (avgConfidence != null && !avgConfidence.isNaN()) {
                                    Text("Overall Confidence: ${(avgConfidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun EventStatCard(title: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier,
        showAccentEdge = true,
        accentColor = color
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = color)
        }
    }
}
