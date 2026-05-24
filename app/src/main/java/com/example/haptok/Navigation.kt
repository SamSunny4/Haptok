package com.example.haptok

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.haptok.ui.screens.HomeScreen
import com.example.haptok.ui.screens.PlayerScreen
import com.example.haptok.ui.screens.SettingsScreen
import com.example.haptok.ui.screens.WaveformDetailScreen
import com.example.haptok.ui.screens.WaveformScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onNavigateToPlayer = { videoUri, hapticMode -> backStack.add(Player(videoUri, hapticMode)) },
                    onNavigateToSettings = { backStack.add(Settings) },
                    onNavigateToWaveforms = { backStack.add(Waveforms) },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Player> { key ->
                PlayerScreen(
                    videoUri = key.videoUri,
                    hapticMode = key.hapticMode,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Waveforms> {
                WaveformScreen(
                    onNavigateToDetail = { id -> backStack.add(WaveformDetail(id)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<WaveformDetail> { key ->
                WaveformDetailScreen(
                    cacheId = key.cacheId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
