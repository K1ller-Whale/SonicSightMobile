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
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import com.k1llerwhale.sonicsight.data.model.FrameKind
import com.k1llerwhale.sonicsight.data.model.ModelProfile
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
    private val CAPTURE_RATE = ModelProfile.CAPTURE_RATE  // the only rate Android guarantees
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Active model profile. Stream/playback rate, decimation factor, frame
    // rate and frame kind all come from here; switching models cancels the
    // stream and reopens it — never mid-stream.
    private var profile: ModelProfile = ModelProfile.DEFAULT

    // JPEG-encode instrumentation (the multisensory profile needs ~30
    // encodes/s, up from 16 — measured, not assumed; summary logged every 5 s)
    private var perfWindowStartMs = 0L
    private var perfFramesArrived = 0
    private var perfFramesSent = 0
    private var perfEncodeMsTotal = 0L
    private var perfEncodeMsMax = 0L

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

        GrpcModule.init(this)
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

        // 3. Setup Buttons
        binding.btnRecord.setOnClickListener {
            if (!isRecording) {
                startLiveStreaming()
            } else {
                stopLiveStreaming()
            }
        }
        binding.btnModel.text = profile.displayName
        binding.btnModel.setOnClickListener { switchModel() }
        binding.btnSettings.setOnClickListener { showHostDialog() }
    }

    /**
     * Switch to the next model. Protocol: cancel the stream, select, reopen
     * with the new metadata — never switch mid-stream. In-flight results
     * from the old stream are dropped by the ViewModel's model_id filter.
     */
    private fun switchModel() {
        val next = ModelProfile.next(profile)
        if (isRecording) {
            stopLiveStreaming()
            viewModel.selectModel(next)
            // Give the mic HAL and the old stream time to release before
            // reopening with the incompatible new capture profile.
            binding.btnRecord.postDelayed({ startLiveStreaming() }, 700)
        } else {
            viewModel.selectModel(next)
        }
    }

    private fun showHostDialog() {
        if (isRecording) {
            Toast.makeText(this, "Stop streaming before changing the server", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(GrpcModule.currentHost())
            hint = "Server IP or hostname"
        }
        AlertDialog.Builder(this)
            .setTitle("SonicSight server")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                GrpcModule.setHost(this, input.text.toString())
                Toast.makeText(this, "Server: ${GrpcModule.currentHost()}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        // Playback rate comes from the model profile: 11025 Hz on the
        // sonicsight branch, 22050 Hz on the multisensory branch.
        val playbackRate = profile.streamRate
        val minBufferSize = AudioTrack.getMinBufferSize(
            playbackRate,
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
            .setSampleRate(playbackRate)
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
            sampleRate = playbackRate,
            initialBufferMs = 200,
            maxBufferMs = 1500
        )
        rightJitterBuffer = JitterBuffer(
            audioTrack = audioTrackRight!!,
            sampleRate = playbackRate,
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
                    val (first, second) = profile.streamLabels
                    binding.tvStatus.text =
                        "Listening (${profile.displayName})… first result in ~${profile.expectedFirstResultMs / 1000.0}s — tap overlay to solo $first/$second"
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
            // Stream labels come from the model profile: Left/Right for
            // Sound of Pixels, On-screen/Off-screen for multisensory.
            val (first, second) = profile.streamLabels
            val modeText = when (mode) {
                PlaybackMode.BOTH -> "Audio: BOTH"
                PlaybackMode.LEFT_ONLY -> "Audio: ${first.uppercase()}"
                PlaybackMode.RIGHT_ONLY -> "Audio: ${second.uppercase()}"
            }
            binding.tvAudioSelection.text = modeText
            if (isRecording) {
                Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.currentProfile.observe(this) { p ->
            profile = p
            binding.btnModel.text = p.displayName
        }

        viewModel.noLocalization.observe(this) { gated ->
            if (gated) {
                // Honest state: the model is not confident about WHERE the
                // sound is. Clear the overlay instead of showing garbage;
                // separated audio keeps playing.
                binding.ivHeatmapOverlay.setImageDrawable(null)
                if (isRecording) {
                    binding.tvStatus.text = "Listening — no confident on-screen source"
                }
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

        // Reset encode instrumentation for this session
        perfWindowStartMs = 0L
        perfFramesArrived = 0
        perfFramesSent = 0
        perfEncodeMsTotal = 0
        perfEncodeMsMax = 0

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

            // Throttle to the profile's frame rate (125 ms = 8 fps for
            // sonicsight, ~33 ms = 30 fps for multisensory)
            var lastAnalyzedTimestamp = 0L
            val frameIntervalMs = profile.frameIntervalMs
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                perfFramesArrived++
                val currentTimestamp = SystemClock.elapsedRealtime()
                if (currentTimestamp - lastAnalyzedTimestamp >= frameIntervalMs) {
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

        // 3. Prepare frame(s) per the model profile and compress
        val chunk: StreamChunk = if (profile.frameKind == FrameKind.FULL_LETTERBOXED) {
            val fullJpeg = com.k1llerwhale.sonicsight.util.ImageTransform.letterboxAndCompress(bitmap)
            StreamChunk.newBuilder()
                .setTimestampMs(timestampMs)
                .setFullJpeg(ByteString.copyFrom(fullJpeg))
                .setFrameWidth(224)
                .setFrameHeight(224)
                .build()
        } else {
            // Split, Resize, Crop and Compress exactly like the Python backend did
            val (leftJpegBytes, rightJpegBytes) =
                com.k1llerwhale.sonicsight.util.ImageTransform.processAndCompressHalves(bitmap)
            StreamChunk.newBuilder()
                .setTimestampMs(timestampMs)
                .setLeftJpeg(ByteString.copyFrom(leftJpegBytes))
                .setRightJpeg(ByteString.copyFrom(rightJpegBytes))
                .setFrameWidth(224)
                .setFrameHeight(224)
                .build()
        }
        val prepTime = System.currentTimeMillis() - prepStart
        bitmap.recycle()

        // 4. Send to Backend without decoding overhead
        viewModel.sendStreamChunk(chunk)

        // 5. Encode-load instrumentation: the multisensory profile roughly
        // doubles the JPEG encode rate (16/s -> ~30/s). Measure it instead of
        // assuming it is fine; summary every 5 s under SonicSightPerf.
        perfFramesSent++
        perfEncodeMsTotal += prepTime
        if (prepTime > perfEncodeMsMax) perfEncodeMsMax = prepTime
        val now = SystemClock.elapsedRealtime()
        if (perfWindowStartMs == 0L) perfWindowStartMs = now
        val windowMs = now - perfWindowStartMs
        if (windowMs >= 5000) {
            val sentPerSec = perfFramesSent * 1000.0 / windowMs
            val arrivedPerSec = perfFramesArrived * 1000.0 / windowMs
            val avgEncode = if (perfFramesSent > 0) perfEncodeMsTotal / perfFramesSent else 0
            Log.i(
                "SonicSightPerf",
                "[${profile.id}] frames arrived=%.1f/s sent=%.1f/s (target %.1f/s) | encode avg=%dms max=%dms"
                    .format(arrivedPerSec, sentPerSec, 1000.0 / profile.frameIntervalMs, avgEncode, perfEncodeMsMax)
            )
            perfWindowStartMs = now
            perfFramesArrived = 0
            perfFramesSent = 0
            perfEncodeMsTotal = 0
            perfEncodeMsMax = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        // Output cadence: ~125 ms blocks at the profile's wire rate
        // (11025 Hz sonicsight, 22050 Hz multisensory).
        val streamRate = profile.streamRate
        val decimFactor = profile.decimFactor
        val samplesPerBlock = streamRate / 8                           // 1378 | 2756

        // Capture at a rate Android actually guarantees, then decimate in-app.
        val captureSamplesPerFrame = samplesPerBlock * decimFactor     // 5512 @ 44100 (both)
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
                "then decimate /$decimFactor to $streamRate Hz")

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
                RawAudioDumper(File(dumpDir, "mic_raw_$stamp.wav"), streamRate).also {
                    Log.i("SonicSightDump", "dumping sent audio to ${it.path}")
                }
        } catch (e: Exception) {
            Log.e("SonicSightDump", "could not open dump file: ${e.message}")
            null
        }

        val isHiRate = profile.frameKind == FrameKind.FULL_LETTERBOXED
        audioJob = lifecycleScope.launch(Dispatchers.IO) {
            val decimator = AudioDecimator(
                decimFactor, 121, profile.decimCutoffHz, CAPTURE_RATE.toDouble()
            )
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

                            // audio_pcm (11025 Hz) feeds the sonicsight branch,
                            // audio_pcm_hi (22050 Hz) the multisensory branch.
                            val pcm = ByteString.copyFrom(outBuffer, 0, outBytes)
                            val builder = StreamChunk.newBuilder().setTimestampMs(timestampMs)
                            if (isHiRate) builder.setAudioPcmHi(pcm) else builder.setAudioPcm(pcm)

                            viewModel.sendStreamChunk(builder.build())
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
