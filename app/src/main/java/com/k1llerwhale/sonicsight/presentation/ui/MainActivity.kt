package com.k1llerwhale.sonicsight.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.k1llerwhale.sonicsight.databinding.ActivityMainBinding
import com.k1llerwhale.sonicsight.presentation.viewmodel.MainViewModel
import com.k1llerwhale.sonicsight.presentation.viewmodel.UiState
import com.k1llerwhale.sonicsight.util.MediaUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // CameraX variables
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    // File Tracking (To pass to ResultActivity)
    private var currentRawFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Observe ViewModel State
        observeUiState()

        // 2. Check Permissions & Start Camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // 3. Setup Button
        binding.btnRecord.setOnClickListener {
            if (!isRecording) {
                startRecording()
            }
        }
    }

    private fun observeUiState() {
        viewModel.uiState.observe(this) { state ->
            when(state) {
                is UiState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Record 6s"
                }
                is UiState.Processing -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnRecord.isEnabled = false
                    binding.tvStatus.text = "Preparing Video..."
                }
                is UiState.Uploading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = "Streaming to AI..."
                }
                is UiState.NavigationReady -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Record Again"
                    binding.tvStatus.text = "✅ Done! Opening Results."

                    // TRIGGER NAVIGATION
                    if (currentRawFile != null) {
                        ResultActivity.start(
                            context = this,
                            rawVideo = currentRawFile!!,
                            processedVideo = currentRawFile!!, // Fallback to raw since mobile processing is removed
                            heatmap = state.heatmapFile,
                            audio1 = state.audio1File,
                            audio2 = state.audio2File
                        )
                    } else {
                        Toast.makeText(this, "Error: Missing video file", Toast.LENGTH_SHORT).show()
                    }

                    // Reset state after navigation so we don't navigate again on rotation
                    viewModel.resetState()
                }
                is UiState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = "Retry"
                    binding.tvStatus.text = "❌ Error: ${state.message}"
                    Log.e("SonicSight", "UI Error: ${state.message}")
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
            } catch (exc: Exception) {
                Log.e("SonicSight", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        isRecording = true
        binding.btnRecord.isEnabled = false
        binding.tvStatus.text = "Recording... (Hold steady)"

        // Generate Filename
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        // Track the raw file
        currentRawFile = File(cacheDir, "raw_$name.mp4")

        val outputOptions = FileOutputOptions.Builder(currentRawFile!!).build()

        val recordingBuilder = videoCapture.output.prepareRecording(this, outputOptions)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            recordingBuilder.withAudioEnabled()
        }

        recording = recordingBuilder.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when(recordEvent) {
                is VideoRecordEvent.Start -> {
                    startTimer()
                }
                is VideoRecordEvent.Finalize -> {
                    if (!recordEvent.hasError()) {
                        Log.d("SonicSight", "Capture Success: ${currentRawFile?.length()?.div(1024)} KB")
                        // Proceed to Phase B
                        currentRawFile?.let { processVideo(it) }
                    } else {
                        recording?.close()
                        recording = null
                        Log.e("SonicSight", "Error: ${recordEvent.error}")
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    private fun startTimer() {
        object : CountDownTimer(7000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.btnRecord.text = "${millisUntilFinished / 1000}s..."
            }

            override fun onFinish() {
                recording?.stop()
                recording = null
                isRecording = false
            }
        }.start()
    }

    private fun processVideo(rawFile: File) {
        viewModel.setProcessing()

        CoroutineScope(Dispatchers.IO).launch {
            // Optional: Save RAW to gallery for debugging
            MediaUtils.saveVideoToGallery(applicationContext, rawFile, "RAW")

            withContext(Dispatchers.Main) {
                // Trigger gRPC Upload via ViewModel
                // We send the raw file directly; the server handles FFmpeg now.
                viewModel.uploadToBackend(rawFile)
            }
        }
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
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}