package com.k1llerwhale.sonicsight.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.k1llerwhale.sonicsight.data.repository.GrpcVideoRepository
import com.k1llerwhale.sonicsight.util.MaskProcessor
import com.k1llerwhale.sonicsight.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Define UI States
sealed class UiState {
    object Idle : UiState()
    object Processing : UiState() // Now means "Preparing Video"
    object Uploading : UiState()  // Now means "Streaming to AI"
    data class Error(val message: String) : UiState()
    data class NavigationReady(
        val heatmapFile: File,
        val audio1File: File,
        val audio2File: File
    ) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GrpcVideoRepository()
    private val maskProcessor = MaskProcessor()

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    fun setProcessing() {
        _uiState.value = UiState.Processing
    }

    fun uploadToBackend(rawVideoFile: File) {
        _uiState.value = UiState.Uploading

        viewModelScope.launch {
            val result = repository.processVideo(rawVideoFile)

            result.onSuccess { response ->
                val context = getApplication<Application>().applicationContext

                withContext(Dispatchers.IO) {
                    try {
                        // 1. Process Heatmap Overlays
                        // Note: Proto 'left_heatmap' becomes 'leftHeatmap' in Kotlin stub
                        val leftOverlay = maskProcessor.createOverlay(
                            response.leftHeatmap.toByteArray(),
                            response.leftCenterFrame.toByteArray()
                        )
                        val rightOverlay = maskProcessor.createOverlay(
                            response.rightHeatmap.toByteArray(),
                            response.rightCenterFrame.toByteArray()
                        )

                        if (leftOverlay == null || rightOverlay == null) {
                            withContext(Dispatchers.Main) {
                                _uiState.value = UiState.Error("Failed to process heatmap overlays")
                            }
                            return@withContext
                        }

                        // 2. Stitch and Save Overlay to File
                        val combinedBitmap = maskProcessor.stitchSideBySide(leftOverlay, rightOverlay)
                        val heatmapFile = File(context.cacheDir, "heatmap.jpg")
                        FileOutputStream(heatmapFile).use { out ->
                            combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }

                        // 3. Save PCM Audio as WAV
                        val audio1 = MediaUtils.savePcmToWav(
                            context,
                            response.leftAudioPcm.toByteArray(),
                            "source_left.wav",
                            response.audioSampleRate
                        )
                        val audio2 = MediaUtils.savePcmToWav(
                            context,
                            response.rightAudioPcm.toByteArray(),
                            "source_right.wav",
                            response.audioSampleRate
                        )

                        withContext(Dispatchers.Main) {
                            if (audio1 != null && audio2 != null) {
                                _uiState.value = UiState.NavigationReady(heatmapFile, audio1, audio2)
                            } else {
                                _uiState.value = UiState.Error("Failed to save audio files")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = UiState.Error("Post-processing error: ${e.message}")
                        }
                    }
                }
            }.onFailure { exception ->
                _uiState.value = UiState.Error(exception.message ?: "Unknown Error")
            }
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }
}
