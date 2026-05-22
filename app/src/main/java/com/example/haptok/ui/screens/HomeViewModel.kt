package com.example.haptok.ui.screens

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProcessingStatus {
    NONE,
    PROCESSING,
    COMPLETED,
    FAILED,
}

data class VideoItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val dateModified: Long,
    val size: Long,
    val processingStatus: ProcessingStatus = ProcessingStatus.NONE,
)

data class HomeUiState(
    val videos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasPermission: Boolean = false,
    val errorMessage: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        loadVideos()
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                hasPermission = false,
                errorMessage = "Video permission is required to browse your videos.",
            )
        }
    }

    fun loadVideos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val videos = queryVideos()
                _uiState.update { it.copy(videos = videos, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load videos: ${e.message}",
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val videos = queryVideos()
                _uiState.update { it.copy(videos = videos, isRefreshing = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = "Refresh failed: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun queryVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoItem>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        val contentResolver = getApplication<Application>().contentResolver
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val duration = cursor.getLong(durationColumn)
                val dateModified = cursor.getLong(dateColumn)
                val size = cursor.getLong(sizeColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id,
                )

                // Skip very short clips (< 1 second)
                if (duration > 1000) {
                    videoList.add(
                        VideoItem(
                            uri = contentUri,
                            title = name,
                            durationMs = duration,
                            dateModified = dateModified,
                            size = size,
                        ),
                    )
                }
            }
        }

        videoList
    }
}
