package com.example.haptok

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Home : NavKey

@Serializable
data class Player(val videoUri: String) : NavKey

@Serializable
data object Settings : NavKey
