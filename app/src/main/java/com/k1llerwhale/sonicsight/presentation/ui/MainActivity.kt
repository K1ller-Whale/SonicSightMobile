package com.k1llerwhale.sonicsight.presentation.ui

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.protobuf.ByteString
import com.k1llerwhale.sonicsight.databinding.ActivityMainBinding
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.presentation.viewmodel.MainViewModel
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // CameraX variables
    private lateinit var cameraExecutor: ExecutorService
    private var isRecording = false
    private var recordingStartTime = 0L

    // Audio variables
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private val SAMPLE_RATE = 11025
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Audio Playback
    private var audioTrackLeft: AudioTrack? = null
    private var audioTrackRight: AudioTrack? = null
    private var playbackJobLeft: Job? = null
    private var playbackJobRight: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Observe ViewModel State
        observeUiState()

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

    private fun setupAudioPlayback() {
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

        audioTrackLeft?.play()
        audioTrackRight?.play()

        playbackJobLeft = CoroutineScope(Dispatchers.IO).launch {
            viewModel.leftAudioChunks.collect { chunk ->
                if (isActive) {
                    audioTrackLeft?.write(chunk, 0, chunk.size)
                }
            }
        }

        playbackJobRight = CoroutineScope(Dispatchers.IO).launch {
            viewModel.rightAudioChunks.collect { chunk ->
                if (isActive) {
                    audioTrackRight?.write(chunk, 0, chunk.size)
                }
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
                }
                is UiState.Streaming -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Stop Processing"
                    binding.tvStatus.text = "Streaming... (Waiting for 6s buffer)"
                    binding.ivHeatmapOverlay.visibility = View.VISIBLE
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
            binding.ivHeatmapOverlay.setImageBitmap(bitmap)
            binding.tvStatus.text = "Live Heatmap Active"
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
        recordingStartTime = System.currentTimeMillis()

        // Setup audio playback receivers
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
                // Target low resolution matching our model (256x256 scaled down)
                // 480x480 is a standard safe size
                .setTargetResolution(android.util.Size(480, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Throttle to 8 FPS manually
            var lastAnalyzedTimestamp = 0L
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTimestamp = System.currentTimeMillis()
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

        val startTime = System.currentTimeMillis()
        val timestampMs = startTime - recordingStartTime
        val rotationDegrees = image.imageInfo.rotationDegrees

        // 1. Convert YUV_420_888 to NV21 ByteArray
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // 2. Create JPEG from NV21
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
        var jpegBytes = out.toByteArray()

        val prepTime = System.currentTimeMillis() - startTime

        // 3. Handle Orientation: Rotate if necessary
        // AI model expects an "upright" image to correctly split left/right
        var rotateTime = 0L
        val bitmapForCache: Bitmap

        if (rotationDegrees != 0) {
            val rotateStart = System.currentTimeMillis()
            val originalBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmapForCache = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)

            val rotatedOut = ByteArrayOutputStream()
            bitmapForCache.compress(Bitmap.CompressFormat.JPEG, 70, rotatedOut)
            jpegBytes = rotatedOut.toByteArray()

            originalBitmap.recycle()
            rotateTime = System.currentTimeMillis() - rotateStart
        } else {
            bitmapForCache = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        }

        // Cache the frame locally for later overlay
        viewModel.cacheFrame(timestampMs, bitmapForCache)

        // 4. Send to Backend
        val chunk = StreamChunk.newBuilder()
            .setTimestampMs(timestampMs)
            .setJpegFrame(ByteString.copyFrom(jpegBytes))
            .setFrameWidth(image.width) // Note: Backend uses these to reconstruct
            .setFrameHeight(image.height)
            .build()

        viewModel.sendStreamChunk(chunk)
        Log.d("SonicSightPerf", "Frame Prep: ${prepTime}ms, Rotate: ${rotateTime}ms, Total: ${System.currentTimeMillis() - startTime}ms")
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Ensure buffer is large enough for ~0.25s of audio (11025 * 0.25 * 2 bytes = ~5512 bytes)
        val optimalBufferSize = maxOf(bufferSize, 8192)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            optimalBufferSize
        )

        audioRecord?.startRecording()

        audioJob = CoroutineScope(Dispatchers.IO).launch {
            val audioBuffer = ByteArray(optimalBufferSize)
            while (isActive && isRecording) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readResult > 0) {
                    val timestampMs = System.currentTimeMillis() - recordingStartTime

                    val chunk = StreamChunk.newBuilder()
                        .setTimestampMs(timestampMs)
                        .setAudioPcm(ByteString.copyFrom(audioBuffer, 0, readResult))
                        .build()

                    viewModel.sendStreamChunk(chunk)
                }
            }
        }
    }

    private fun stopLiveStreaming() {
        isRecording = false

        // Stop audio
        audioJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Stop gRPC stream
        val finalChunk = StreamChunk.newBuilder()
            .setIsLast(true)
            .build()
        viewModel.sendStreamChunk(finalChunk)
        viewModel.resetState()

        // Revert camera to simple preview
        startCameraPreview()
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
        cameraExecutor.shutdown()
        audioRecord?.release()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}