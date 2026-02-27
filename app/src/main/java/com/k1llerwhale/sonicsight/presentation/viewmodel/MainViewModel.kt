package com.k1llerwhale.sonicsight.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.k1llerwhale.sonicsight.data.model.PredictionResponse
import com.k1llerwhale.sonicsight.data.repository.VideoRepository
import com.k1llerwhale.sonicsight.util.MediaUtils
import kotlinx.coroutines.launch
import java.io.File

// Define UI States
sealed class UiState {
    object Idle : UiState()
    object Processing : UiState() // FFmpeg running
    object Uploading : UiState()  // Sending to Server
    data class Success(val data: PredictionResponse) : UiState()
    data class Error(val message: String) : UiState()
    data class NavigationReady(
        val heatmapFile: File,
        val audio1File: File,
        val audio2File: File
    ) : UiState()
}

class MainViewModel(private val application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository()

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    fun setProcessing() {
        _uiState.value = UiState.Processing
    }

    fun uploadToBackend(processedFile: File) {
        _uiState.value = UiState.Uploading

        viewModelScope.launch {
            val result = repository.uploadVideo(processedFile)

            if (result.isSuccess) {
                val response = result.getOrThrow()

                // Decode and Save Files (Clean Architecture: Do this here or in Domain layer)
                val context = getApplication<Application>().applicationContext

                val heatmap = MediaUtils.saveBase64ToFile(context, response.heatmapBase64, "heatmap.png")
                val audio1 = MediaUtils.saveBase64ToFile(context, response.audioBase64Num1, "source_A.wav")
                val audio2 = MediaUtils.saveBase64ToFile(context, response.audioBase64Num2, "source_B.wav") // Assuming API sends 2

                if (heatmap != null && audio1 != null && audio2 != null) {
                    _uiState.value = UiState.NavigationReady(heatmap, audio1, audio2)
                } else {
                    _uiState.value = UiState.Error("Failed to decode server response")
                }
            } else {
                _uiState.value = UiState.Error(result.exceptionOrNull()?.message ?: "Unknown Error")
            }
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }
}