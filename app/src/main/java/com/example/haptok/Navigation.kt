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

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    onNavigateToPlayer = { videoUri -> backStack.add(Player(videoUri)) },
                    onNavigateToSettings = { backStack.add(Settings) },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Player> { key ->
                PlayerScreen(
                    videoUri = key.videoUri,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
