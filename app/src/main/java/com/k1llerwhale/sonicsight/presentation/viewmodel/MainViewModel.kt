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

    // Buffering Progress (0 to 100)
    private val _bufferingProgress = MutableLiveData<Int>(0)
    val bufferingProgress: LiveData<Int> = _bufferingProgress

    // Visualizer Levels (0.0 to 1.0)
    private val _leftVolume = MutableLiveData<Float>(0f)
    val leftVolume: LiveData<Float> = _leftVolume

    private val _rightVolume = MutableLiveData<Float>(0f)
    val rightVolume: LiveData<Float> = _rightVolume

    private var heatmapIntensity = 0.7f

    fun setHeatmapIntensity(progress: Int) {
        heatmapIntensity = progress / 100f
    }

    // Raw audio chunks for playback
    val leftAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val rightAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)

    fun setProcessing() {
        _uiState.value = UiState.Processing
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
            _bufferingProgress.postValue((response.bufferingProgress * 100).toInt())
            return
        }

        Log.d("SonicSightPerf", "Received Result for timestamp ${response.timestampMs}ms")

        // Emit audio chunks for playback
        if (response.leftAudioPcm.size() > 0) {
            val leftBytes = response.leftAudioPcm.toByteArray()
            val rightBytes = response.rightAudioPcm.toByteArray()

            // Calculate volume for visualizer
            _leftVolume.postValue(calculateVolume(leftBytes))
            _rightVolume.postValue(calculateVolume(rightBytes))

            leftAudioChunks.emit(leftBytes)
            rightAudioChunks.emit(rightBytes)
        }

        // Render visual heatmaps using LOCAL cached frame (transparent overlay)
        withContext(Dispatchers.IO) {
            val renderStart = System.currentTimeMillis()

            // We use the heatmap data to create a transparent overlay
            // that will be placed directly on top of the camera preview.
            // Since the backend now sends the same mask for left/right in live mode,
            // we only need one overlay.
            val transparentHeatmap = maskProcessor.createTransparentHeatmap(
                response.leftHeatmap.toByteArray()
            )

            if (transparentHeatmap != null) {
                val renderTime = System.currentTimeMillis() - renderStart
                withContext(Dispatchers.Main) {
                    _streamResults.value = transparentHeatmap
                    Log.d("SonicSightPerf", "Total Result Latency: ${System.currentTimeMillis() - receiveTime}ms (Render: ${renderTime}ms)")
                }
            }
        }
    }

    private fun calculateVolume(pcmData: ByteArray): Float {
        if (pcmData.isEmpty()) return 0f
        var sum = 0.0
        // PCM 16-bit is 2 bytes per sample
        for (i in 0 until pcmData.size - 1 step 2) {
            val sample = ((pcmData[i+1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = Math.sqrt(sum / (pcmData.size / 2))
        // Normalize to 0.0 - 1.0 (Approximate max for speech/ambient)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f) * 5f // Multiply for visibility
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
