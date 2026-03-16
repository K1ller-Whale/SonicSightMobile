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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // CameraX variables
    private var isRecording = false
    private var recordingStartTime = 0L
    private var frameJob: Job? = null

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

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isTouching = false
    private var isMuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // 4. Setup Mute Button
        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            val icon = if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
            binding.btnMute.setImageResource(icon)

            // Adjust volume of existing tracks
            val volume = if (isMuted) 0.0f else 1.0f
            audioTrackLeft?.setStereoVolume(volume, 0.0f)
            audioTrackRight?.setStereoVolume(0.0f, volume)
        }

        // 5. Setup Touch Listener for surgical audio separation
        setupTouchListener()

        // 5. Setup Intensity Control
        binding.sbIntensity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setHeatmapIntensity(progress)
                binding.ivHeatmapOverlay.alpha = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.viewFinder.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    isTouching = true
                    // Normalize coordinates 0.0 to 1.0
                    lastTouchX = event.x / view.width
                    lastTouchY = event.y / view.height

                    // Update UI indicator
                    binding.vTouchIndicator.visibility = View.VISIBLE

                    // Use actual width/height if available, otherwise fallback to dp-to-px estimate
                    val indicatorWidth = if (binding.vTouchIndicator.width > 0) binding.vTouchIndicator.width else (60 * resources.displayMetrics.density).toInt()
                    val indicatorHeight = if (binding.vTouchIndicator.height > 0) binding.vTouchIndicator.height else (60 * resources.displayMetrics.density).toInt()

                    binding.vTouchIndicator.x = event.x - indicatorWidth / 2f
                    binding.vTouchIndicator.y = event.y - indicatorHeight / 2f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    binding.vTouchIndicator.visibility = View.GONE
                }
            }
            true // Consume event
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
        // Route left channel audio and respect mute state
        val volume = if (isMuted) 0.0f else 1.0f
        audioTrackLeft?.setStereoVolume(volume, 0.0f)

        audioTrackRight = AudioTrack(
            audioAttributes,
            audioFormat,
            playBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        // Route right channel audio and respect mute state
        audioTrackRight?.setStereoVolume(0.0f, volume)

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
        var isCurrentlyBuffering = true

        viewModel.uiState.observe(this) { state ->
            when(state) {
                is UiState.Idle -> {
                    isCurrentlyBuffering = true
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Start Live Processing"
                    binding.ivHeatmapOverlay.visibility = View.GONE
                    binding.llVisualizer.visibility = View.GONE
                    binding.sbIntensity.visibility = View.GONE
                    binding.vTouchIndicator.visibility = View.GONE
                    binding.tvHint.visibility = View.GONE
                    binding.btnMute.visibility = View.GONE
                }
                is UiState.Streaming -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Stop Processing"
                    binding.tvStatus.text = "Initializing Stream..."
                    binding.ivHeatmapOverlay.visibility = View.VISIBLE
                    binding.llVisualizer.visibility = View.VISIBLE
                    binding.sbIntensity.visibility = View.VISIBLE
                    binding.tvHint.visibility = View.VISIBLE
                    binding.btnMute.visibility = View.VISIBLE
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
            isCurrentlyBuffering = false
            binding.ivHeatmapOverlay.setImageBitmap(bitmap)
            binding.tvStatus.text = if (isTouching) "Surgical Focus Active" else "Spatial Stereo Active"
        }

        // Observe Buffering Progress
        viewModel.bufferingProgress.observe(this) { progress ->
            if (isCurrentlyBuffering && progress < 100) {
                binding.tvStatus.text = "Buffering: $progress%"
            }
        }

        // Observe Volume for Visualizer
        viewModel.leftVolume.observe(this) { level ->
            val params = binding.vLevelLeft.layoutParams
            val maxHeightPx = (100 * resources.displayMetrics.density).toInt()
            params.height = (level * maxHeightPx).toInt()
            binding.vLevelLeft.layoutParams = params
        }

        viewModel.rightVolume.observe(this) { level ->
            val params = binding.vLevelRight.layoutParams
            val maxHeightPx = (100 * resources.displayMetrics.density).toInt()
            params.height = (level * maxHeightPx).toInt()
            binding.vLevelRight.layoutParams = params
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

        // Start Frame Capture Loop (8 FPS)
        // Using PreviewView.bitmap ensures the frame matches the screen alignment/crop exactly
        frameJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isRecording) {
                val startTime = System.currentTimeMillis()
                val bitmap = binding.viewFinder.bitmap

                if (bitmap != null) {
                    val timestampMs = System.currentTimeMillis() - recordingStartTime

                    // Process in IO thread
                    withContext(Dispatchers.IO) {
                        try {
                            val out = ByteArrayOutputStream()
                            // Downscale to 224x224 to match the AI model input exactly
                            val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

                            try {
                                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                val jpegBytes = out.toByteArray()

                                val chunk = StreamChunk.newBuilder()
                                    .setTimestampMs(timestampMs)
                                    .setJpegFrame(ByteString.copyFrom(jpegBytes))
                                    .setFrameWidth(bitmap.width)
                                    .setFrameHeight(bitmap.height)
                                    .setTouchX(lastTouchX)
                                    .setTouchY(lastTouchY)
                                    .setIsTouching(isTouching)
                                    .build()

                                viewModel.sendStreamChunk(chunk)
                            } finally {
                                if (scaled != bitmap) scaled.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e("SonicSight", "Frame processing failed", e)
                        } finally {
                            bitmap.recycle() // CRITICAL: Fix memory leak
                        }
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val delayTime = maxOf(0L, 125L - elapsed)
                kotlinx.coroutines.delay(delayTime)
            }
        }

        // Setup Audio Capture
        startAudioCapture()
    }

    private fun stopLiveStreaming() {
        isRecording = false

        // Stop frame capture
        frameJob?.cancel()
        frameJob = null

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

        // Camera is already running preview, no need to restart
    }

    // Removed processImageProxy and original imageAnalysis logic

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
        audioRecord?.release()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}