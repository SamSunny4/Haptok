package com.example.haptok.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.haptok.cache.CachedHapticTrack
import com.example.haptok.cache.HapticCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WaveformUiState(
    val waveforms: List<CachedHapticTrack> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class WaveformViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WaveformUiState())
    val uiState: StateFlow<WaveformUiState> = _uiState.asStateFlow()

    private val cacheManager = HapticCacheManager(application.applicationContext)
    private val context = application.applicationContext

    init {
        loadWaveforms()
    }

    fun loadWaveforms() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val cached = cacheManager.getAllCached()
                _uiState.update { it.copy(waveforms = cached, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load waveforms: ${e.message}",
                    )
                }
            }
        }
    }

    fun deleteWaveform(id: Long) {
        viewModelScope.launch {
            try {
                cacheManager.deleteCached(id)
                loadWaveforms()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.message}") }
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
}
