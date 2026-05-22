package com.example.haptok.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.haptok.theme.GlassBorder
import com.example.haptok.theme.GlassHighlight
import com.example.haptok.theme.GlassWhite
import com.example.haptok.theme.HaptokViolet

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    showAccentEdge: Boolean = false,
    accentColor: Color = HaptokViolet,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val glassBrush = Brush.verticalGradient(
        colors = listOf(
            GlassWhite,
            GlassHighlight,
        ),
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(glassBrush)
            .then(
                if (showAccentEdge) {
                    Modifier.border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.6f),
                                GlassBorder,
                                GlassBorder.copy(alpha = 0.1f),
                            ),
                        ),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = GlassBorder,
                        shape = shape,
                    )
                }
            )
            .padding(contentPadding),
        content = content,
    )
}
