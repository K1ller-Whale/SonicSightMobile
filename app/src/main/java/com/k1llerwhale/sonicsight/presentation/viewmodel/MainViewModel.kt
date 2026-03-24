package com.k1llerwhale.sonicsight.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.k1llerwhale.sonicsight.data.repository.GrpcVideoRepository
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.util.MaskProcessor
import com.k1llerwhale.sonicsight.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Define UI States
sealed class UiState {
    object Idle : UiState()
    object Processing : UiState() // Now means "Preparing Video"
    object Uploading : UiState()  // Now means "Streaming to AI"
    object Streaming : UiState()  // Near Real-Time mode
    data class Error(val message: String) : UiState()
    data class NavigationReady(
        val heatmapFile: File,
        val audio1File: File,
        val audio2File: File
    ) : UiState()
}

enum class PlaybackMode {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GrpcVideoRepository()
    private val maskProcessor = MaskProcessor()

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    // Bidirectional Streaming Channels
    // Using explicit parameters for MutableSharedFlow to avoid ambiguity
    val outStreamChunks = MutableSharedFlow<StreamChunk>(replay = 0, extraBufferCapacity = 64)

    // Live results for UI overlay
    private val _streamResults = MutableLiveData<Bitmap>()
    val streamResults: LiveData<Bitmap> = _streamResults

    // Raw audio chunks for playback
    val leftAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val rightAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)

    private val _playbackMode = MutableLiveData(PlaybackMode.BOTH)
    val playbackMode: LiveData<PlaybackMode> = _playbackMode

    fun setProcessing() {
        _uiState.value = UiState.Processing
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _playbackMode.value = mode
    }

    /**
     * Start the bidirectional stream. Call this right before starting capture.
     */
    fun startStreaming() {
        _uiState.value = UiState.Streaming

        viewModelScope.launch {
            repository.streamProcess(outStreamChunks)
                .catch { e ->
                    _uiState.postValue(UiState.Error("Stream error: ${e.message}"))
                }
                .collect { response ->
                    handleStreamResult(response)
                }
        }
    }

    /**
     * Send a chunk to the server.
     */
    fun sendStreamChunk(chunk: StreamChunk) {
        outStreamChunks.tryEmit(chunk)
    }

    private suspend fun handleStreamResult(response: StreamResult) {
        val receiveTime = System.currentTimeMillis()
        if (!response.success) {
            withContext(Dispatchers.Main) {
                _uiState.value = UiState.Error(response.errorMessage)
            }
            return
        }

        if (response.isBuffering) {
            return
        }

        Log.d("SonicSightPerf", "Received Result for timestamp ${response.timestampMs}ms")

        // Emit audio chunks for playback (non-blocking — drop if buffer full rather than suspend)
        if (response.leftAudioPcm.size() > 0) {
            leftAudioChunks.emit(response.leftAudioPcm.toByteArray())
            rightAudioChunks.emit(response.rightAudioPcm.toByteArray())
        }

        // Render visual heatmaps using LOCAL cached frame (transparent overlay)
        withContext(Dispatchers.IO) {
            val renderStart = System.currentTimeMillis()

            // We use the heatmap data to create a transparent overlay
            // that will be placed directly on top of the camera preview.
            // Since the backend outputs separate stereoscopic masks, we render and stitch them natively.
            val leftTransparent = maskProcessor.createTransparentHeatmap(response.leftHeatmap.toByteArray())
            val rightTransparent = maskProcessor.createTransparentHeatmap(response.rightHeatmap.toByteArray())

            if (leftTransparent != null && rightTransparent != null) {
                // Stitch stereoscopic views horizontally natively on Android
                val stitched = maskProcessor.stitchSideBySide(leftTransparent, rightTransparent)
                // Recycle intermediate bitmaps immediately to prevent memory leak
                leftTransparent.recycle()
                rightTransparent.recycle()
                val renderTime = System.currentTimeMillis() - renderStart
                withContext(Dispatchers.Main) {
                    _streamResults.value = stitched
                    Log.d("SonicSightPerf", "Total Result Latency: ${System.currentTimeMillis() - receiveTime}ms (Render: ${renderTime}ms)")
                }
            } else {
                // Recycle if only one was created
                leftTransparent?.recycle()
                rightTransparent?.recycle()
            }
        }
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
