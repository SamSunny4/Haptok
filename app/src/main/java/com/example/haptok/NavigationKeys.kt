package com.example.haptok

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Home : NavKey

@Serializable
data class Player(val videoUri: String, val hapticMode: String) : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object Waveforms : NavKey

@Serializable
data class WaveformDetail(val cacheId: Long) : NavKey
