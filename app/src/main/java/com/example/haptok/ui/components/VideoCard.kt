package com.example.haptok.ui.components

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.DarkSurfaceVariant
import com.example.haptok.theme.HaptokBlue
import com.example.haptok.theme.HaptokViolet
import com.example.haptok.theme.StatusComplete
import com.example.haptok.theme.StatusError
import com.example.haptok.theme.StatusProcessing
import com.example.haptok.theme.TextPrimary
import com.example.haptok.theme.TextSecondary
import com.example.haptok.ui.screens.ProcessingStatus

@Composable
fun VideoCard(
    title: String,
    uri: Uri,
    durationMs: Long,
    processingStatus: ProcessingStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "card_scale",
    )

    val context = LocalContext.current
    val thumbnail = remember(uri) {
        try {
            context.contentResolver.loadThumbnail(
                uri,
                Size(320, 240),
                null,
            )
        } catch (_: Exception) {
            null
        }
    }

    GlassCard(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = HaptokViolet.copy(alpha = 0.3f)),
                onClick = onClick,
            )
            .testTag("video_card_${uri.lastPathSegment}"),
        cornerRadius = 16.dp,
        showAccentEdge = processingStatus == ProcessingStatus.COMPLETED,
        contentPadding = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail or gradient placeholder
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    DarkSurfaceVariant,
                                    HaptokViolet.copy(alpha = 0.2f),
                                ),
                            ),
                        ),
                )
            }

            // Dark scrim at bottom for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                    ),
            )

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp, end = 48.dp),
            )

            // Duration badge
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Processing status badge
            if (processingStatus != ProcessingStatus.NONE) {
                val (statusColor, statusLabel) = when (processingStatus) {
                    ProcessingStatus.PROCESSING -> StatusProcessing to "Processing"
                    ProcessingStatus.COMPLETED -> StatusComplete to "Ready"
                    ProcessingStatus.FAILED -> StatusError to "Failed"
                    else -> Color.Transparent to ""
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
