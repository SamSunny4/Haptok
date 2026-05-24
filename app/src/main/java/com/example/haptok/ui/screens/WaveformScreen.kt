package com.example.haptok.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.haptok.cache.CachedHapticTrack
import com.example.haptok.theme.DarkBackground
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
fun WaveformScreen(
    onNavigateToDetail: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WaveformViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                text = "Saved Waveforms",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaptokViolet)
                }
            }
            uiState.waveforms.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Waves,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No saved waveforms",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Generate haptics using the AI server to save them here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.waveforms, key = { it.id }) { track ->
                        WaveformItem(
                            track = track,
                            onClick = { onNavigateToDetail(track.id) },
                            onExport = { viewModel.exportWaveform(track.id) },
                            onDelete = { viewModel.deleteWaveform(track.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WaveformItem(
    track: CachedHapticTrack,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    val dateString = dateFormat.format(Date(track.createdAt))
    val durationSec = track.videoDurationSeconds.toInt()
    val min = durationSec / 60
    val sec = durationSec % 60

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        showAccentEdge = true,
        accentColor = HaptokCyan
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(HaptokViolet.copy(alpha = 0.2f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Waves, contentDescription = null, tint = HaptokCyan)
            }
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = track.videoTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$min:${sec.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = " • $dateString",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }

            IconButton(onClick = onExport) {
                Icon(Icons.Default.Download, contentDescription = "Export", tint = HaptokCyan)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
            }
        }
    }
}
