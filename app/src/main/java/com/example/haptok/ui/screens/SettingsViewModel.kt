package com.example.haptok.ui.screens

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.haptok.cache.HapticCacheManager
import com.example.haptok.haptics.HapticCapabilityManager
import com.example.haptok.models.DeviceProfile
import com.example.haptok.network.NetworkModule
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val serverUrl: String = "http://192.168.1.8:8000",
    val hapticIntensity: Float = 0.75f,
    val fallbackEnabled: Boolean = true,
    val forceNativeHaptics: Boolean = false,
    val latencyOffsetMs: Int = -30,
    val deviceProfile: DeviceProfile? = null,
    val cacheCount: Int = 0,
    val isCacheClearing: Boolean = false,
)

/**
 * ViewModel for the Settings screen.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "haptok_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_FALLBACK_ENABLED = "fallback_enabled"
        private const val KEY_FORCE_NATIVE = "force_native_haptics"
        private const val KEY_LATENCY_OFFSET = "latency_offset_ms"
        private const val DEFAULT_SERVER_URL = "http://192.168.1.8:8000"
    }

    private val context = application.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val cacheManager = HapticCacheManager(context)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val profile = HapticCapabilityManager.getProfile(context)
        _uiState.value = SettingsUiState(
            serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            hapticIntensity = prefs.getFloat(KEY_HAPTIC_INTENSITY, 0.75f),
            fallbackEnabled = prefs.getBoolean(KEY_FALLBACK_ENABLED, true),
            forceNativeHaptics = prefs.getBoolean(KEY_FORCE_NATIVE, false),
            latencyOffsetMs = prefs.getInt(KEY_LATENCY_OFFSET, -30),
            deviceProfile = profile,
        )
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(cacheCount = cacheManager.getCacheCount())
        }
    }

    fun updateServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
        _uiState.value = _uiState.value.copy(serverUrl = url)
        NetworkModule.getInstance(context).updateBaseUrl(url)
    }

    fun updateHapticIntensity(intensity: Float) {
        prefs.edit().putFloat(KEY_HAPTIC_INTENSITY, intensity).apply()
        _uiState.value = _uiState.value.copy(hapticIntensity = intensity)
    }

    fun toggleFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALLBACK_ENABLED, enabled).apply()
        _uiState.value = _uiState.value.copy(fallbackEnabled = enabled)
    }

    fun toggleForceNative(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_NATIVE, enabled).apply()
        _uiState.value = _uiState.value.copy(forceNativeHaptics = enabled)
    }

    fun updateLatencyOffset(offsetMs: Int) {
        prefs.edit().putInt(KEY_LATENCY_OFFSET, offsetMs).apply()
        _uiState.value = _uiState.value.copy(latencyOffsetMs = offsetMs)
    }

    fun clearCache() {
        _uiState.value = _uiState.value.copy(isCacheClearing = true)
        viewModelScope.launch {
            cacheManager.clearAll()
            _uiState.value = _uiState.value.copy(cacheCount = 0, isCacheClearing = false)
        }
    }
}
