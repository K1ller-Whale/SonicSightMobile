package com.k1llerwhale.sonicsight.presentation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.protobuf.ByteString
import com.k1llerwhale.sonicsight.data.api.GrpcModule
import com.k1llerwhale.sonicsight.databinding.ActivityMainBinding
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.presentation.viewmodel.MainViewModel
import com.k1llerwhale.sonicsight.presentation.viewmodel.PlaybackMode
import com.k1llerwhale.sonicsight.presentation.viewmodel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import com.k1llerwhale.sonicsight.util.AudioDecimator
import com.k1llerwhale.sonicsight.util.JitterBuffer
import com.k1llerwhale.sonicsight.util.RawAudioDumper
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // CameraX variables
    private lateinit var cameraExecutor: ExecutorService
    private var isRecording = false
    private var recordingStartTime = 0L

    // Audio variables
    private var audioRecord: AudioRecord? = null
    private var micDumper: RawAudioDumper? = null
    private var audioJob: Job? = null
    private var capDumper: RawAudioDumper? = null
    private val SAMPLE_RATE = 11025          // stream + playback rate the server expects
    private val CAPTURE_RATE = 44100         // the only rate Android guarantees on AudioRecord
    private val DECIM_FACTOR = 4             // 44100 / 4 = 11025 exactly
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Audio Playback
    private var audioTrackLeft: AudioTrack? = null
    private var audioTrackRight: AudioTrack? = null
    private var leftJitterBuffer: JitterBuffer? = null
    private var rightJitterBuffer: JitterBuffer? = null
    private var playbackJobLeft: Job? = null
    private var playbackJobRight: Job? = null
    @Volatile
    private var currentPlaybackMode: PlaybackMode = PlaybackMode.BOTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Observe ViewModel State
        observeUiState()
        setupOverlayTapSelection()

        // 2. Check Permissions & Start Camera Preview
        if (allPermissionsGranted()) {
            startCameraPreview()
        } else {
            requestPermissions()
        }

        // 3. Setup Button
        binding.btnRecord.setOnClickListener {
            if (!isRecording) {
                startLiveStreaming()
            } else {
                stopLiveStreaming()
            }
        }
    }

    private fun cleanupAudioPlayback() {
        playbackJobLeft?.cancel()
        playbackJobRight?.cancel()
        playbackJobLeft = null
        playbackJobRight = null

        // Stop jitter buffers first (they own the AudioTrack.write loop)
        leftJitterBuffer?.stop()
        rightJitterBuffer?.stop()
        leftJitterBuffer = null
        rightJitterBuffer = null

        try { audioTrackLeft?.stop() } catch (_: Exception) {}
        audioTrackLeft?.release()
        audioTrackLeft = null

        try { audioTrackRight?.stop() } catch (_: Exception) {}
        audioTrackRight?.release()
        audioTrackRight = null
    }

    private fun setupAudioPlayback() {
        // Clean up any previous playback resources first
        cleanupAudioPlayback()

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Ensure buffer is reasonably large to prevent underruns
        val playBufferSize = maxOf(minBufferSize, 8192)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        audioTrackLeft = AudioTrack(
            audioAttributes,
            audioFormat,
            playBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        // Route left channel audio strictly to the left speaker/headphone
        audioTrackLeft?.setStereoVolume(1.0f, 0.0f)

        audioTrackRight = AudioTrack(
            audioAttributes,
            audioFormat,
            playBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        // Route right channel audio strictly to the right speaker/headphone
        audioTrackRight?.setStereoVolume(0.0f, 1.0f)

        // Don't call audioTrack.play() here — JitterBuffer will call it after initial buffering
        applyPlaybackMode(currentPlaybackMode)

        // Create jitter buffers that absorb network jitter and feed AudioTrack at a steady rate.
        // Initial buffer of 200ms adapts upward if underruns are detected.
        leftJitterBuffer = JitterBuffer(
            audioTrack = audioTrackLeft!!,
            sampleRate = SAMPLE_RATE,
            initialBufferMs = 200,
            maxBufferMs = 1500
        )
        rightJitterBuffer = JitterBuffer(
            audioTrack = audioTrackRight!!,
            sampleRate = SAMPLE_RATE,
            initialBufferMs = 200,
            maxBufferMs = 1500
        )

        leftJitterBuffer?.start()
        rightJitterBuffer?.start()

        // Collectors just enqueue into jitter buffers — non-blocking, no direct AudioTrack access
        playbackJobLeft = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.leftAudioChunks.collect { chunk ->
                if (isActive) {
                    leftJitterBuffer?.write(chunk)
                }
            }
        }

        playbackJobRight = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.rightAudioChunks.collect { chunk ->
                if (isActive) {
                    rightJitterBuffer?.write(chunk)
                }
            }
        }
    }

    private fun applyPlaybackMode(mode: PlaybackMode) {
        currentPlaybackMode = mode
        when (mode) {
            PlaybackMode.BOTH -> {
                audioTrackLeft?.setVolume(1.0f)
                audioTrackRight?.setVolume(1.0f)
            }
            PlaybackMode.LEFT_ONLY -> {
                audioTrackLeft?.setVolume(1.0f)
                audioTrackRight?.setVolume(0.0f)
            }
            PlaybackMode.RIGHT_ONLY -> {
                audioTrackLeft?.setVolume(0.0f)
                audioTrackRight?.setVolume(1.0f)
            }
        }
    }

    private fun setupOverlayTapSelection() {
        binding.ivHeatmapOverlay.setOnTouchListener { view, event ->
            if (!isRecording) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    if (view.width <= 0) return@setOnTouchListener false

                    val tappedMode = if (event.x < (view.width / 2f)) {
                        PlaybackMode.LEFT_ONLY
                    } else {
                        PlaybackMode.RIGHT_ONLY
                    }

                    val nextMode = if (currentPlaybackMode == tappedMode) {
                        PlaybackMode.BOTH
                    } else {
                        tappedMode
                    }
                    // Give instant physical feedback for source selection.
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.setPlaybackMode(nextMode)
                    true
                }
                else -> false
            }
        }
    }

    private fun observeUiState() {
        viewModel.uiState.observe(this) { state ->
            when(state) {
                is UiState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Start Live Processing"
                    binding.ivHeatmapOverlay.visibility = View.GONE
                    binding.tvAudioSelection.visibility = View.GONE
                }
                is UiState.Streaming -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Stop Processing"
                    binding.tvStatus.text = "Live Heatmap Active - Tap left/right overlay to solo source"
                    binding.ivHeatmapOverlay.visibility = View.VISIBLE
                    binding.tvAudioSelection.visibility = View.VISIBLE
                }
                is UiState.Error -> {
                    stopLiveStreaming()
                    binding.tvStatus.text = "❌ Error: ${state.message}"
                    Log.e("SonicSight", "UI Error: ${state.message}")
                }
                else -> {}
            }
        }

        viewModel.streamResults.observe(this) { bitmap ->
            // Don't recycle the old bitmap here — it may still be in the ImageView's
            // draw pipeline, causing 'Canvas: trying to use a recycled bitmap' crashes.
            // The GC will reclaim it once the ImageView releases its reference.
            binding.ivHeatmapOverlay.setImageBitmap(bitmap)
            binding.tvStatus.text = "Live Heatmap Active"
        }

        viewModel.playbackMode.observe(this) { mode ->
            applyPlaybackMode(mode)
            binding.tvAudioSelection.text = when (mode) {
                PlaybackMode.BOTH -> "Audio: BOTH"
                PlaybackMode.LEFT_ONLY -> "Audio: LEFT"
                PlaybackMode.RIGHT_ONLY -> "Audio: RIGHT"
            }
            if (isRecording) {
                val toastText = when (mode) {
                    PlaybackMode.BOTH -> "Audio mode: BOTH"
                    PlaybackMode.LEFT_ONLY -> "Audio mode: LEFT"
                    PlaybackMode.RIGHT_ONLY -> "Audio mode: RIGHT"
                }
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (exc: Exception) {
                Log.e("SonicSight", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLiveStreaming() {
        if (!allPermissionsGranted()) return

        isRecording = true
        recordingStartTime = SystemClock.elapsedRealtime()

        // Setup audio playback receivers
        viewModel.setPlaybackMode(PlaybackMode.BOTH)
        setupAudioPlayback()

        // Tell ViewModel to open gRPC stream
        viewModel.startStreaming()

        // Setup CameraX ImageAnalysis (instead of VideoCapture)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                // Target 16:9 landscape resolution (720p is universally supported and fast/safe)
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Throttle to 8 FPS manually
            var lastAnalyzedTimestamp = 0L
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTimestamp = SystemClock.elapsedRealtime()
                if (currentTimestamp - lastAnalyzedTimestamp >= 125) { // 1000ms / 8fps = 125ms
                    processImageProxy(imageProxy)
                    lastAnalyzedTimestamp = currentTimestamp
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("SonicSight", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))

        // Setup Audio Capture
        startAudioCapture()
    }

    private fun processImageProxy(image: ImageProxy) {
        if (!isRecording) return

        val startTime = SystemClock.elapsedRealtime()
        val timestampMs = startTime - recordingStartTime
        val rotationDegrees = image.imageInfo.rotationDegrees

        // 1. Convert YUV to Bitmap natively
        var bitmap = com.k1llerwhale.sonicsight.util.ImageTransform.imageProxyToBitmap(image)
        if (bitmap == null) {
            return
        }

        // 2. Handle Orientation: Rotate if necessary
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
                bitmap = rotatedBitmap
            }
        }
        
        val prepStart = System.currentTimeMillis()

        // 3. Process, Split, Resize, Crop and Compress exactly like the Python backend did
        val (leftJpegBytes, rightJpegBytes) = com.k1llerwhale.sonicsight.util.ImageTransform.processAndCompressHalves(bitmap)
        val prepTime = System.currentTimeMillis() - prepStart
        bitmap.recycle()

        // 4. Send to Backend without decoding overhead
        val chunk = StreamChunk.newBuilder()
            .setTimestampMs(timestampMs)
            .setLeftJpeg(ByteString.copyFrom(leftJpegBytes))
            .setRightJpeg(ByteString.copyFrom(rightJpegBytes))
            .setFrameWidth(224)
            .setFrameHeight(224)
            .build()

        viewModel.sendStreamChunk(chunk)
        Log.d("SonicSightPerf", "Prep & Compress: ${prepTime}ms, Total: ${SystemClock.elapsedRealtime() - startTime}ms")
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        // Output cadence: ~125 ms at 8 fps, at the 11025 Hz rate the server expects.
        val samplesPerFrame = SAMPLE_RATE / 8                          // 1378

        // Capture at a rate Android actually guarantees, then decimate in-app.
        val captureSamplesPerFrame = samplesPerFrame * DECIM_FACTOR    // 5512 @ 44100
        val captureBytesPerFrame = captureSamplesPerFrame * 2          // 11024
        val minBuf = AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val captureBufferSize = maxOf(minBuf, captureBytesPerFrame * 4)

        // Prefer sources that bypass OEM voice tuning (noise suppression, AGC,
        // narrowband voice EQ). MIC is the last resort because it is the one
        // most aggressively processed by the vendor HAL.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedSupported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        Log.i("SonicSightDump", "UNPROCESSED supported by device: $unprocessedSupported")

        val candidates = ArrayList<Pair<String, Int>>()
        if (unprocessedSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            candidates.add(Pair("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED))
        }
        candidates.add(Pair("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER))
        candidates.add(Pair("MIC", MediaRecorder.AudioSource.MIC))

        var chosenName = "none"
        var opened: AudioRecord? = null
        for ((name, source) in candidates) {
            val candidate = try {
                AudioRecord(source, CAPTURE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, captureBufferSize)
            } catch (e: Exception) {
                Log.w("SonicSightDump", "source $name threw: ${e.message}")
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                opened = candidate
                chosenName = name
                break
            }
            try { candidate?.release() } catch (e: Exception) {}
            Log.w("SonicSightDump", "source $name unavailable, trying next")
        }

        if (opened == null) {
            Log.e("SonicSightDump", "no usable audio source at $CAPTURE_RATE Hz")
            return
        }

        val record: AudioRecord = opened
        audioRecord = record
        record.startRecording()

        Log.i("SonicSightDump", "AudioRecord source=$chosenName state=${record.state} " +
                "requested=$CAPTURE_RATE actual=${record.sampleRate} " +
                "ch=${record.channelCount} fmt=${record.audioFormat} " +
                "minBuf=$minBuf used=$captureBufferSize " +
                "then decimate /$DECIM_FACTOR to $SAMPLE_RATE Hz")

        // DIAGNOSTIC: two dumps, so the filter can be judged on its own.
        //   mic_cap44k = exactly what the hardware handed us, before our filter
        //   mic_raw    = exactly what goes on the wire, after our filter
        val dumpDir = try {
            File(getExternalFilesDir(null), "sonicsight").apply { mkdirs() }
        } catch (e: Exception) {
            Log.e("SonicSightDump", "could not create dump dir: ${e.message}")
            null
        }
        val stamp = System.currentTimeMillis()

        capDumper = try {
            if (dumpDir == null) null else
                RawAudioDumper(File(dumpDir, "mic_cap44k_$stamp.wav"), CAPTURE_RATE).also {
                    Log.i("SonicSightDump", "dumping raw capture to ${it.path}")
                }
        } catch (e: Exception) {
            Log.e("SonicSightDump", "could not open capture dump: ${e.message}")
            null
        }

        micDumper = try {
            if (dumpDir == null) null else
                RawAudioDumper(File(dumpDir, "mic_raw_$stamp.wav"), SAMPLE_RATE).also {
                    Log.i("SonicSightDump", "dumping sent audio to ${it.path}")
                }
        } catch (e: Exception) {
            Log.e("SonicSightDump", "could not open dump file: ${e.message}")
            null
        }

        audioJob = lifecycleScope.launch(Dispatchers.IO) {
            val decimator = AudioDecimator(DECIM_FACTOR, 121, 5000.0, CAPTURE_RATE.toDouble())
            decimator.reset()
            val captureBuffer = ByteArray(captureBytesPerFrame)
            val outBuffer = ByteArray(decimator.maxOutputBytes(captureBytesPerFrame))
            while (isActive && isRecording) {
                try {
                    val readResult = audioRecord?.read(captureBuffer, 0, captureBuffer.size) ?: 0
                    if (readResult > 0) {
                        val timestampMs = SystemClock.elapsedRealtime() - recordingStartTime

                        capDumper?.write(captureBuffer, readResult)

                        val outBytes = decimator.process(captureBuffer, readResult, outBuffer)
                        if (outBytes > 0) {
                            // identical bytes and count as the chunk below
                            micDumper?.write(outBuffer, outBytes)

                            val chunk = StreamChunk.newBuilder()
                                .setTimestampMs(timestampMs)
                                .setAudioPcm(ByteString.copyFrom(outBuffer, 0, outBytes))
                                .build()

                            viewModel.sendStreamChunk(chunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SonicSight", "Audio capture error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopLiveStreaming() {
        isRecording = false
        binding.btnRecord.isEnabled = false // Disable to prevent rapid toggling

        // Stop audio capture synchronously to release mic immediately
        val currentAudioJob = audioJob
        val currentAudioRecord = audioRecord
        audioJob = null
        audioRecord = null

        currentAudioJob?.cancel()
        try { currentAudioRecord?.stop() } catch (e: Exception) {}
        currentAudioRecord?.release()

        // DIAGNOSTIC: finalize the raw mic WAV
        micDumper?.close()
        micDumper = null
        capDumper?.close()
        capDumper = null

        // Stop audio playback
        cleanupAudioPlayback()

        // Stop gRPC stream
        val finalChunk = StreamChunk.newBuilder()
            .setIsLast(true)
            .build()
        viewModel.sendStreamChunk(finalChunk)
        viewModel.resetState()

        // Revert camera to simple preview
        startCameraPreview()

        // Re-enable button after HAL has time to clean up
        binding.btnRecord.postDelayed({
            binding.btnRecord.isEnabled = true
        }, 500)
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCameraPreview()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAudioPlayback()
        cameraExecutor.shutdown()
        audioRecord?.release()
        GrpcModule.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}
