package com.k1llerwhale.sonicsight.presentation.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.k1llerwhale.sonicsight.data.model.ModelProfile
import com.k1llerwhale.sonicsight.data.repository.GrpcVideoRepository
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.util.MaskProcessor
import com.k1llerwhale.sonicsight.util.MediaUtils
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * Test seam S5 (docs/SEAMS.md): repository, dispatchers and clock are
 * constructor-injected with production defaults. @JvmOverloads keeps the
 * (Application)-only constructor the default ViewModelProvider factory
 * needs, so the Activity's creation path is byte-for-byte unchanged.
 */
class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: GrpcVideoRepository = GrpcVideoRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {

    private val maskProcessor = MaskProcessor()

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    // Bidirectional Streaming Channels
    // Recreated per stream in startStreaming(), so chunks buffered for a
    // cancelled stream never leak into the next one (the capture profiles
    // are incompatible between models).
    private var outStreamChunks = MutableSharedFlow<StreamChunk>(replay = 0, extraBufferCapacity = 128)
    private var streamJob: Job? = null

    // Selected model. The id is volatile because handleStreamResult compares
    // it against every echoed model_id on a background collector.
    private val _currentProfile = MutableLiveData(ModelProfile.DEFAULT)
    val currentProfile: LiveData<ModelProfile> = _currentProfile

    @Volatile
    private var selectedModelId: String = ModelProfile.DEFAULT.id

    // True while a confidence-gated model reports no confident localization
    // (empty heatmap + low cam_confidence). Audio keeps playing.
    private val _noLocalization = MutableLiveData(false)
    val noLocalization: LiveData<Boolean> = _noLocalization

    // ── Touch (pixel) mode ──
    /** Region audio answered by the server for a tap. */
    data class QueryAudio(val queryId: Int, val pcm: ByteArray, val energy: Float, val error: String)

    val queryAudio = MutableSharedFlow<QueryAudio>(replay = 0, extraBufferCapacity = 64)

    /** Grid dimensions from the latest result — never hard-coded. */
    @Volatile
    var gridDims: Pair<Int, Int> = 14 to 14
        private set

    /** A discovered source: colour is identity, never an instrument name. */
    data class SourceInfo(
        val id: Int, val rgb: Int, val energy: Float,
        val cx: Float, val cy: Float,
    )

    private val _sources = MutableLiveData<List<SourceInfo>>(emptyList())
    val sources: LiveData<List<SourceInfo>> = _sources

    /** Client-side solo: id of the source whose region audio is playing
     *  alone, or null for the full mix. */
    private val _soloedSource = MutableLiveData<Int?>(null)
    val soloedSource: LiveData<Int?> = _soloedSource

    fun soloSource(s: SourceInfo?) {
        _soloedSource.value = s?.id
        if (s != null) {
            // Solo = LIVE follow at the source's centroid (~1.5-cell disc,
            // an approximation of the cluster support): the stream's audio
            // becomes this source until un-soloed. Not a one-shot replay.
            startFollow(s.cx, s.cy, 1.5f / gridDims.first)
        } else {
            stopFollow()
        }
    }

    /** True once at least one pixel overlay was rendered this stream —
     *  lets a pre-first-result freeze still show the first map instead of
     *  wedging the UI in 'buffering' (review finding). */
    @Volatile
    private var hasPixelOverlay = false

    /** Freeze is level-triggered on the wire: the activity stamps this onto
     *  every outgoing frame chunk. While frozen the overlay is pinned
     *  client-side and window_id=0 queries resolve to the pinned window. */
    @Volatile
    var frozen: Boolean = false
        private set

    private val _frozenLive = MutableLiveData(false)
    val frozenLive: LiveData<Boolean> = _frozenLive

    private var nextQueryId = 1

    fun setFrozen(value: Boolean) {
        frozen = value
        _frozenLive.value = value
    }

    /** Send a tap/drag query. Suspending emit — a query must never be
     *  silently dropped by a saturated out-queue. `sticky` turns the query
     *  into a live follow: the stream's audio becomes this region instead
     *  of the mixture until stopFollow(). */
    fun sendPixelQuery(xNorm: Float, yNorm: Float, radiusNorm: Float, sticky: Boolean = false) {
        val q = com.k1llerwhale.sonicsight.grpc.PixelQuery.newBuilder()
            .setQueryId(nextQueryId++)
            .setXNorm(xNorm)
            .setYNorm(yNorm)
            .setRadiusNorm(radiusNorm)
            .setWindowId(0)  // newest; server redirects to the pinned window while frozen
            .setSticky(sticky)
            .build()
        val chunk = StreamChunk.newBuilder()
            .addQueries(q)
            .setFreeze(frozen)
            .setRequestClusters(true)
            .build()
        viewModelScope.launch { outStreamChunks.emit(chunk) }
    }

    /** True while the live stream is following a selected region. */
    private val _following = MutableLiveData(false)
    val following: LiveData<Boolean> = _following

    fun startFollow(xNorm: Float, yNorm: Float, radiusNorm: Float) {
        _following.value = true
        sendPixelQuery(xNorm, yNorm, radiusNorm, sticky = true)
    }

    fun stopFollow() {
        if (_following.value == true) {
            _following.value = false
            val chunk = StreamChunk.newBuilder()
                .setClearSticky(true)
                .setFreeze(frozen)
                .setRequestClusters(true)
                .build()
            viewModelScope.launch { outStreamChunks.emit(chunk) }
        }
    }

    // Live results for UI overlay
    private val _streamResults = MutableLiveData<Bitmap>()
    val streamResults: LiveData<Bitmap> = _streamResults

    // Raw audio chunks for playback
    val leftAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val rightAudioChunks = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)

    private val _playbackMode = MutableLiveData(PlaybackMode.BOTH)
    val playbackMode: LiveData<PlaybackMode> = _playbackMode

    // Sequence tracking for gap detection
    private var lastSeqNumber = -1
    private var lastResultTimeMs = 0L

    fun setProcessing() {
        _uiState.value = UiState.Processing
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _playbackMode.value = mode
    }

    /**
     * Select a model. Only call while not streaming — the switch protocol is
     * cancel the stream, then reopen with the new metadata (the activity
     * orchestrates stop/start around this).
     */
    fun selectModel(profile: ModelProfile) {
        selectedModelId = profile.id
        _currentProfile.value = profile
    }

    /**
     * Start the bidirectional stream for the selected model. Call this right
     * before starting capture.
     */
    fun startStreaming() {
        stopStreaming()
        _uiState.value = UiState.Streaming
        _noLocalization.value = false
        setFrozen(false)  // a freeze armed while idle must not wedge the stream
        _following.value = false
        _soloedSource.value = null
        hasPixelOverlay = false
        lastSeqNumber = -1
        lastResultTimeMs = 0L

        // Fresh out-flow per stream: never replay old-profile chunks.
        outStreamChunks = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        val chunks = outStreamChunks
        val modelId = selectedModelId

        streamJob = viewModelScope.launch {
            repository.streamProcess(chunks, modelId)
                .catch { e ->
                    _uiState.postValue(UiState.Error("Stream error: ${e.message}"))
                }
                .collect { response ->
                    handleStreamResult(response)
                }
        }
    }

    /** Cancel the active stream, if any. In-flight results from it are also
     *  dropped by the model_id filter in handleStreamResult. */
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * Send a FRAME chunk to the server. tryEmit is deliberate: when the
     * out-queue is saturated a dropped frame is harmless (the next one is
     * 33-125 ms away) and blocking the camera analyzer would be worse.
     */
    fun sendStreamChunk(chunk: StreamChunk) {
        outStreamChunks.tryEmit(chunk)
    }

    /**
     * Send an AUDIO chunk to the server, losslessly. Audio must never be
     * dropped: the server's window timeline is built from received samples,
     * and every lost chunk makes buffered audio fall behind the video
     * timestamps until no valid inference window exists — audible as
     * stuttering and "skipping non-monotonic window" storms server-side.
     * At 30 fps the frame flood can saturate the queue, so audio uses a
     * suspending emit and waits for space instead of vanishing.
     */
    suspend fun sendAudioChunk(chunk: StreamChunk) {
        outStreamChunks.emit(chunk)
    }

    @VisibleForTesting
    internal suspend fun handleStreamResult(response: StreamResult) {
        val receiveTime = clock()

        // Drop results whose echoed model id does not match the current
        // selection — this is what discards in-flight results from a
        // cancelled stream after a model switch. Empty model_id means a
        // pre-registry server: accept for backward compatibility.
        val echoedModel = response.modelId
        if (echoedModel.isNotEmpty() && echoedModel != selectedModelId) {
            Log.d("SonicSight", "Dropping stale result from model '$echoedModel'")
            return
        }

        if (!response.success) {
            withContext(mainDispatcher) {
                _uiState.value = UiState.Error(response.errorMessage)
            }
            return
        }

        if (response.isBuffering) {
            return
        }

        // Connection health monitoring
        if (lastResultTimeMs > 0) {
            val gap = receiveTime - lastResultTimeMs
            if (gap > 500) {
                Log.w("SonicSight", "Network degraded: ${gap}ms between results")
            }
        }
        lastResultTimeMs = receiveTime

        Log.d("SonicSightPerf", "Received Result seq=${response.sequenceNumber} timestamp=${response.timestampMs}ms")

        // Gap detection: if sequence numbers skip, insert silence to maintain playback timing.
        // This prevents the audio timeline from compressing when results are genuinely lost.
        if (response.leftAudioPcm.size() > 0 && lastSeqNumber >= 0 && response.sequenceNumber > lastSeqNumber + 1) {
            val missedResults = response.sequenceNumber - lastSeqNumber - 1
            // Use audioSampleCount from the current result as an estimate for missed chunk sizes
            val samplesPerResult = if (response.audioSampleCount > 0) response.audioSampleCount else 1250
            val silenceBytes = missedResults * samplesPerResult * 2  // PCM16 = 2 bytes/sample
            val silence = ByteArray(silenceBytes)
            leftAudioChunks.emit(silence)
            rightAudioChunks.emit(silence)
            Log.w("SonicSight", "Gap detected: inserted $missedResults silence chunks (seq ${lastSeqNumber + 1}..${response.sequenceNumber - 1})")
        }
        if (response.sequenceNumber > 0) {
            lastSeqNumber = response.sequenceNumber
        }

        // Emit audio chunks for playback
        if (response.leftAudioPcm.size() > 0) {
            leftAudioChunks.emit(response.leftAudioPcm.toByteArray())
            rightAudioChunks.emit(response.rightAudioPcm.toByteArray())
        }

        // ── Touch (pixel) mode: energy map + query answers, no legacy heatmaps ──
        val activeProfile = _currentProfile.value ?: ModelProfile.DEFAULT
        if (activeProfile.isPixel) {
            for (pa in response.pixelAudioList) {
                queryAudio.tryEmit(
                    QueryAudio(pa.queryId, pa.pcm.toByteArray(), pa.energy, pa.error)
                )
            }
            // Frozen pins the DISPLAYED overlay — but the very first map of a
            // stream still renders, or freezing early wedges 'buffering'.
            if (response.energyMap.size() > 0 && (!frozen || !hasPixelOverlay)) {
                if (response.gridWidth > 0 && response.gridHeight > 0) {
                    gridDims = response.gridWidth to response.gridHeight
                }
                val maxEnergy = response.clustersList.maxOfOrNull { it.energy } ?: 0f
                val infos = response.clustersList.map {
                    SourceInfo(
                        it.clusterId, it.rgb,
                        if (maxEnergy > 0f) it.energy / maxEnergy else 0f,
                        it.centroidX, it.centroidY,
                    )
                }
                withContext(mainDispatcher) { _sources.value = infos }
                withContext(ioDispatcher) {
                    val colors = response.clustersList.associate { it.clusterId to it.rgb }
                    val overlay = maskProcessor.createPixelOverlay(
                        response.energyMap.toByteArray(),
                        response.gridWidth,
                        response.gridHeight,
                        if (response.clusterLabels.size() > 0) response.clusterLabels.toByteArray() else null,
                        colors,
                    )
                    if (overlay != null) {
                        hasPixelOverlay = true
                        withContext(mainDispatcher) { _streamResults.value = overlay }
                    }
                }
            }
            return
        }

        // Render visual heatmaps using LOCAL cached frame (transparent overlay)
        withContext(ioDispatcher) {
            val renderStart = clock()

            val profile = _currentProfile.value ?: ModelProfile.DEFAULT
            val heatmapCount =
                if (response.heatmapCount > 0) response.heatmapCount else profile.heatmapCount

            if (heatmapCount == 1) {
                // Single full-frame map rides in left_heatmap. An EMPTY map on
                // a confidence-gated model is the server's honest "no
                // confident localization" — clear the overlay, keep the audio.
                if (response.leftHeatmap.isEmpty) {
                    withContext(mainDispatcher) { _noLocalization.value = true }
                    return@withContext
                }
                val overlay = maskProcessor.createTransparentHeatmap(response.leftHeatmap.toByteArray())
                if (overlay != null) {
                    val renderTime = clock() - renderStart
                    withContext(mainDispatcher) {
                        _noLocalization.value = false
                        _streamResults.value = overlay
                        Log.d("SonicSightPerf", "Total Result Latency: ${clock() - receiveTime}ms (Render: ${renderTime}ms)")
                    }
                }
                return@withContext
            }

            // heatmapCount == 2: separate left/right masks, stitched natively.
            val leftTransparent = maskProcessor.createTransparentHeatmap(response.leftHeatmap.toByteArray())
            val rightTransparent = maskProcessor.createTransparentHeatmap(response.rightHeatmap.toByteArray())

            if (leftTransparent != null && rightTransparent != null) {
                // Stitch stereoscopic views horizontally natively on Android
                val stitched = maskProcessor.stitchSideBySide(leftTransparent, rightTransparent)
                // Recycle intermediate bitmaps immediately to prevent memory leak
                leftTransparent.recycle()
                rightTransparent.recycle()
                val renderTime = clock() - renderStart
                withContext(mainDispatcher) {
                    _noLocalization.value = false
                    _streamResults.value = stitched
                    Log.d("SonicSightPerf", "Total Result Latency: ${clock() - receiveTime}ms (Render: ${renderTime}ms)")
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

                withContext(ioDispatcher) {
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
                            withContext(mainDispatcher) {
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

                        withContext(mainDispatcher) {
                            if (audio1 != null && audio2 != null) {
                                _uiState.value = UiState.NavigationReady(heatmapFile, audio1, audio2)
                            } else {
                                _uiState.value = UiState.Error("Failed to save audio files")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(mainDispatcher) {
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
        stopStreaming()
        _uiState.value = UiState.Idle
        _noLocalization.value = false
        setFrozen(false)
        lastSeqNumber = -1
        lastResultTimeMs = 0L
    }
}
