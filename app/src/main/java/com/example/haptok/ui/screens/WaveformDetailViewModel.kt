package com.example.haptok.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.haptok.cache.CachedHapticTrack
import com.example.haptok.cache.HapticCacheManager
import com.example.haptok.models.HapticTimeline
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WaveformDetailUiState(
    val track: CachedHapticTrack? = null,
    val timeline: HapticTimeline? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class WaveformDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WaveformDetailUiState())
    val uiState: StateFlow<WaveformDetailUiState> = _uiState.asStateFlow()

    private val cacheManager = HapticCacheManager(application.applicationContext)
    private val context = application.applicationContext
    
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val timelineAdapter = moshi.adapter(HapticTimeline::class.java)

    fun loadDetail(cacheId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val cached = cacheManager.getCacheById(cacheId)
                if (cached != null) {
                    val timeline = timelineAdapter.fromJson(cached.hapticTimelineJson)
                    _uiState.update { it.copy(track = cached, timeline = timeline, isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Waveform not found") }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load detail: ${e.message}",
                    )
                }
            }
        }
    }

    fun exportWaveform(id: Long) {
        viewModelScope.launch {
            try {
                val file = cacheManager.exportToFile(id)
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        Toast.makeText(context, "Exported to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun deleteWaveform(id: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                cacheManager.deleteCached(id)
                withContext(Dispatchers.Main) {
                    onDeleted()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.message}") }
            }
        }
    }
}
