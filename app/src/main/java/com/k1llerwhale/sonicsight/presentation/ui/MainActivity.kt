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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.protobuf.ByteString
import com.k1llerwhale.sonicsight.R
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
    private var hasFirstResult = false

    // Camera frame dimensions as delivered to the transform (post-rotation);
    // the touch coordinate chain needs the real numbers, not assumptions.
    @Volatile private var lastFrameW = 0
    @Volatile private var lastFrameH = 0

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
        // Must run before super.onCreate(): it draws the launch theme's mark and
        // then swaps this activity to postSplashScreenTheme (Theme.SonicSight).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        GrpcModule.init(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Observe ViewModel State
        observeUiState()

        // 2. Check Permissions & Start Camera Preview
        if (allPermissionsGranted()) {
            startCameraPreview()
        } else {
            requestPermissions()
        }

        // 3. Setup Controls
        binding.btnRecord.setOnClickListener {
            if (!isRecording) {
                startLiveStreaming()
            } else {
                stopLiveStreaming()
            }
        }
        binding.btnSettings.setOnClickListener { showHostDialog() }

        binding.modelGroup.check(modelButtonIdFor(profile))
        binding.modelGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = when (checkedId) {
                binding.btnModelSpeech.id -> ModelProfile.MULTISENSORY
                binding.btnModelTouch.id -> ModelProfile.SONICSIGHT_PIXEL
                else -> ModelProfile.SONICSIGHT
            }
            if (target.id != profile.id) switchModel(target)
        }

        binding.btnFreeze.setOnClickListener {
            val next = !viewModel.frozen
            viewModel.setFrozen(next)
            binding.btnFreeze.text =
                getString(if (next) R.string.unfreeze else R.string.freeze)
        }

        setupPixelTouch()
        collectQueryAudio()

        // Re-register the overlay matrix on any layout change (multi-window
        // resize, fold posture) — a result-time-only matrix goes stale,
        // especially while frozen (review finding).
        binding.ivHeatmapOverlay.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (profile.isPixel && (r - l != or_ - ol || b - t != ob - ot) &&
                lastPixelBmpW > 0
            ) {
                applyPixelOverlayMatrix(lastPixelBmpW, lastPixelBmpH)
            }
        }

        binding.soloGroup.check(binding.btnSoloBoth.id)
        binding.soloGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.btnSoloFirst.id -> PlaybackMode.LEFT_ONLY
                binding.btnSoloSecond.id -> PlaybackMode.RIGHT_ONLY
                else -> PlaybackMode.BOTH
            }
            viewModel.setPlaybackMode(mode)
        }
    }

    /**
     * Switch model. Protocol: cancel the stream, select, reopen with the
     * new metadata — never switch mid-stream. In-flight results from the
     * old stream are dropped by the ViewModel's model_id filter.
     */
    private fun switchModel(target: ModelProfile) {
        if (isRecording) {
            binding.tvStatus.text = getString(R.string.state_switching, target.displayName)
            stopLiveStreaming()
            viewModel.selectModel(target)
            // Give the mic HAL and the old stream time to release before
            // reopening with the incompatible new capture profile.
            binding.btnRecord.postDelayed({ startLiveStreaming() }, 700)
        } else {
            viewModel.selectModel(target)
        }
    }

    /**
     * Register the letterbox-space energy bitmap onto the fillCenter camera
     * preview: bitmap px -> letterbox px -> frame px (inverse of
     * letterboxAndCompress) -> view px (forward fillCenter). One affine
     * transform; without it the overlay would stretch the grey letterbox
     * rows across the visible frame and every cell would sit off its scene
     * content.
     */
    private var lastPixelBmpW = 0
    private var lastPixelBmpH = 0

    private fun applyPixelOverlayMatrix(bmpW: Int, bmpH: Int) {
        lastPixelBmpW = bmpW
        lastPixelBmpH = bmpH
        val fw = lastFrameW.toFloat(); val fh = lastFrameH.toFloat()
        val vw = binding.ivHeatmapOverlay.width.toFloat()
        val vh = binding.ivHeatmapOverlay.height.toFloat()
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return
        val dim = 224f
        val s1 = dim / maxOf(fw, fh)               // frame -> letterbox scale
        val lbOffX = (dim - fw * s1) / 2f
        val lbOffY = (dim - fh * s1) / 2f
        val s2 = maxOf(vw / fw, vh / fh)           // frame -> view (fillCenter)
        val vOffX = (vw - fw * s2) / 2f
        val vOffY = (vh - fh * s2) / 2f
        // Per-axis bitmap -> letterbox scale: the map covers the full square,
        // and the axes only match while the wire grid is square.
        val sx = (dim / bmpW) / s1 * s2
        val sy = (dim / bmpH) / s1 * s2
        val tx = -lbOffX / s1 * s2 + vOffX
        val ty = -lbOffY / s1 * s2 + vOffY
        val m = Matrix()
        m.setScale(sx, sy)
        m.postTranslate(tx, ty)
        binding.ivHeatmapOverlay.imageMatrix = m

        // The lattice shares the exact same registration: letterbox origin at
        // (tx, ty), one 16-letterbox-px cell = 16/s1*s2 view px, lattice
        // drawn over the content rows only.
        val g = viewModel.gridDims
        val cellLb = dim / g.first
        val rowTop = (lbOffY / cellLb).toInt()
        val rowBottom = kotlin.math.ceil((lbOffY + fh * s1) / cellLb).toInt() - 1
        binding.gridLattice.setGridTransform(
            tx, ty, cellLb / s1 * s2, g.first, rowTop, rowBottom,
        )
    }

    private fun modelButtonIdFor(p: ModelProfile) = when (p.id) {
        ModelProfile.MULTISENSORY.id -> binding.btnModelSpeech.id
        ModelProfile.SONICSIGHT_PIXEL.id -> binding.btnModelTouch.id
        else -> binding.btnModelMusic.id
    }

    /** Touch-to-query: the tested coordinate chain (view -> fillCenter
     *  inverse -> letterbox norm), one query per tap or per grid cell
     *  crossed in a drag. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPixelTouch() {
        var lastCell: Pair<Int, Int>? = null
        var downNorm: com.k1llerwhale.sonicsight.util.CoordinateMap.Norm? = null
        var downCell: Pair<Int, Int>? = null
        var dragged = false
        var longPressFired = false
        var longPressRunnable: Runnable? = null

        binding.ivHeatmapOverlay.setOnTouchListener { view, event ->
            if (!profile.isPixel || !isRecording) return@setOnTouchListener false
            val fw = lastFrameW; val fh = lastFrameH
            if (fw <= 0 || fh <= 0) return@setOnTouchListener false
            val norm = com.k1llerwhale.sonicsight.util.CoordinateMap.viewToQueryNorm(
                event.x, event.y,
                view.width.toFloat(), view.height.toFloat(),
                fw.toFloat(), fh.toFloat(),
            )
            val g = viewModel.gridDims
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    norm ?: return@setOnTouchListener true
                    val cell = com.k1llerwhale.sonicsight.util.CoordinateMap.normToCell(n = norm, g)
                    downNorm = norm; downCell = cell; lastCell = cell
                    dragged = false; longPressFired = false
                    // Hold to FOLLOW: the live stream becomes this region.
                    longPressRunnable = Runnable {
                        if (!dragged) {
                            longPressFired = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            binding.gridLattice.setStickyCell(cell.first, cell.second)
                            viewModel.startFollow(norm.x, norm.y, 1.0f / g.first)
                        }
                    }
                    view.postDelayed(longPressRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    norm ?: return@setOnTouchListener true
                    val cell = com.k1llerwhale.sonicsight.util.CoordinateMap.normToCell(n = norm, g)
                    if (cell != lastCell) {
                        dragged = true
                        longPressRunnable?.let { view.removeCallbacks(it) }
                        lastCell = cell
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        binding.gridLattice.showTappedCell(cell.first, cell.second, reducedMotion())
                        viewModel.sendPixelQuery(norm.x, norm.y, 1.0f / g.first)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { view.removeCallbacks(it) }
                    // Short tap without drag: one-shot region replay (the
                    // last couple of seconds, filtered to the cell).
                    if (!longPressFired && !dragged &&
                        event.actionMasked == MotionEvent.ACTION_UP
                    ) {
                        val cell = downCell; val dn = downNorm
                        if (cell != null && dn != null) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            binding.gridLattice.showTappedCell(cell.first, cell.second, reducedMotion())
                            viewModel.sendPixelQuery(dn.x, dn.y, 1.0f / g.first)
                        }
                    }
                    lastCell = null; downCell = null; downNorm = null
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun com.k1llerwhale.sonicsight.util.CoordinateMap.normToCell(
        n: com.k1llerwhale.sonicsight.util.CoordinateMap.Norm, g: Pair<Int, Int>,
    ) = normToCell(n.x, n.y, g.first, g.second)

    /** Play a query's region audio once, ducking the mixture underneath. */
    private fun collectQueryAudio() {
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.queryAudio.collect { qa ->
                if (qa.error.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = getString(R.string.query_error, qa.error)
                    }
                    return@collect
                }
                if (qa.pcm.isEmpty()) return@collect
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text =
                        if (qa.energy < QUERY_SILENCE_ENERGY) getString(R.string.query_nothing_here)
                        else getString(R.string.query_playing)
                }
                if (qa.energy < QUERY_SILENCE_ENERGY) return@collect
                playQueryPcm(qa.pcm)
            }
        }
    }

    private var queryTrack: AudioTrack? = null
    private var duckGeneration = 0

    private fun playQueryPcm(pcm: ByteArray) {
        try {
            queryTrack?.release()
            val rate = profile.streamRate
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                pcm.size, AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track.write(pcm, 0, pcm.size)
            queryTrack = track
            // Duck the live mixture while the region plays. The restore is
            // generation-tagged: a newer query's duck must not be undone by
            // an older query's timer firing mid-playback (review finding).
            val generation = ++duckGeneration
            audioTrackLeft?.setVolume(0.15f)
            track.play()
            val durationMs = pcm.size / 2 * 1000L / rate
            binding.btnRecord.postDelayed({
                if (generation == duckGeneration) {
                    audioTrackLeft?.setVolume(1.0f)
                }
            }, durationMs + 100)
        } catch (e: Exception) {
            Log.e("SonicSight", "query playback failed: ${e.message}")
        }
    }

    /** One chip per discovered source: a colour dot whose fill height is the
     *  source's relative energy. Tap to hear it alone; tap again for the
     *  full mix. Colour is identity — sources are never named. */
    private fun renderSourceRail(sources: List<com.k1llerwhale.sonicsight.presentation.viewmodel.MainViewModel.SourceInfo>) {
        val rail = binding.sourceRail
        rail.removeAllViews()
        if (!profile.isPixel) return
        val soloed = viewModel.soloedSource.value
        val density = resources.displayMetrics.density
        for (s in sources) {
            val dot = View(this)
            val size = (28 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (10 * density).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF000000.toInt() or s.rgb)
                // Energy as ring thickness is unreadable at 28dp; encode it
                // as alpha instead: quiet sources sit back, loud sit forward.
                alpha = (80 + 175 * s.energy).toInt().coerceIn(80, 255)
                if (s.id == soloed) {
                    setStroke((3 * density).toInt(), 0xFFFCFDBF.toInt())  // glow ring = playing alone
                }
            }
            dot.background = bg
            val colorName = getString(
                when (s.rgb) {
                    0x4477AA -> R.string.color_blue
                    0xCCBB44 -> R.string.color_sand
                    else -> R.string.color_purple
                }
            )
            dot.contentDescription =
                if (s.id == soloed) getString(R.string.cd_source_chip_soloed, colorName)
                else getString(R.string.cd_source_chip, colorName, (s.energy * 100).toInt())
            dot.isClickable = true
            dot.isFocusable = true
            dot.setOnClickListener {
                if (viewModel.soloedSource.value == s.id) {
                    viewModel.soloSource(null)          // back to the mix
                    releaseQueryTrack()
                    audioTrackLeft?.setVolume(1.0f)
                } else {
                    viewModel.soloSource(s)             // region audio arrives as a query answer
                }
            }
            rail.addView(dot)
        }
    }

    private fun releaseQueryTrack() {
        // Stop region audio with the session — Stop must actually stop
        // (review finding: the static track outlived the stream).
        duckGeneration++
        try { queryTrack?.stop() } catch (_: Exception) {}
        queryTrack?.release()
        queryTrack = null
    }

    /** True when the system requests no/short animations. */
    private fun reducedMotion(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    private fun showHostDialog() {
        if (isRecording) {
            Toast.makeText(this, getString(R.string.settings_stop_first), Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(GrpcModule.currentHost())
            hint = getString(R.string.settings_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(input)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                GrpcModule.setHost(this, input.text.toString())
                Toast.makeText(this, "Server: ${GrpcModule.currentHost()}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun cleanupAudioPlayback() {
        releaseQueryTrack()
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

    // NOTE: the old tap-on-overlay-halves solo gesture is gone deliberately.
    // On the speech model the two streams mean on-screen/off-screen, which
    // is not spatial — a left/right tap zone would lie. The solo chips are
    // the one control for this on both models, labelled from the profile.

    private fun observeUiState() {
        // Registered FIRST: LiveData redelivers sticky values in registration
        // order, and a retained pixel overlay must not render before the
        // profile (scale type, matrix mode) is in place after recreation.
        viewModel.currentProfile.observe(this) { p ->
            profile = p
            // Stream labels and legend meaning come from the profile:
            // Left/Right + Loud/Quiet for music, On-screen/Off-screen +
            // Matches audio/No match for speech. Never hard-coded.
            binding.btnSoloFirst.text = p.streamLabels.first
            binding.btnSoloSecond.text = p.streamLabels.second
            binding.tvLegendHigh.text = p.heatmapMeaning.first
            binding.tvLegendLow.text = p.heatmapMeaning.second
            val modelButtonId = modelButtonIdFor(p)
            if (binding.modelGroup.checkedButtonId != modelButtonId) {
                binding.modelGroup.check(modelButtonId)
            }
            // Touch mode: solo chips make no sense (mixture + tap queries);
            // freeze becomes available; the overlay switches from the fitXY
            // stretch to a computed matrix that registers the letterboxed
            // energy map onto the fillCenter camera preview.
            binding.soloGroup.visibility = if (p.isPixel) View.GONE else View.VISIBLE
            binding.btnFreeze.visibility = if (p.isPixel) View.VISIBLE else View.GONE
            binding.btnFreeze.isEnabled = isRecording
            binding.ivHeatmapOverlay.scaleType =
                if (p.isPixel) android.widget.ImageView.ScaleType.MATRIX
                else android.widget.ImageView.ScaleType.FIT_XY
            // In touch mode the overlay is the input surface, not decoration:
            // it must be reachable by accessibility services. (Full TalkBack
            // explore-by-touch UX is Phase 6 design work; this makes the
            // surface exist for it.)
            binding.ivHeatmapOverlay.importantForAccessibility =
                if (p.isPixel) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            binding.ivHeatmapOverlay.contentDescription =
                if (p.isPixel) getString(R.string.cd_touch_surface) else getString(R.string.cd_viewfinder)
            binding.ivHeatmapOverlay.isClickable = p.isPixel
            binding.gridLattice.visibility = if (p.isPixel) View.VISIBLE else View.GONE
            binding.sourceRail.visibility = if (p.isPixel) View.VISIBLE else View.GONE
            binding.tvModeHint.text = getString(
                when (p.id) {
                    ModelProfile.MULTISENSORY.id -> R.string.mode_hint_speech
                    ModelProfile.SONICSIGHT_PIXEL.id -> R.string.mode_hint_touch
                    else -> R.string.mode_hint_halves
                }
            )
        }

        viewModel.sources.observe(this) { sources -> renderSourceRail(sources) }
        viewModel.soloedSource.observe(this) { renderSourceRail(viewModel.sources.value ?: emptyList()) }

        viewModel.uiState.observe(this) { state ->
            when(state) {
                is UiState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    binding.btnRecord.text = getString(R.string.start_listening)
                    binding.tvStatus.text = getString(R.string.state_ready)
                    binding.ivHeatmapOverlay.visibility = View.GONE
                    binding.tvGate.visibility = View.GONE
                    binding.modelGroup.isEnabled = true
                    binding.btnFreeze.isEnabled = false  // nothing to pin while idle
                }
                is UiState.Streaming -> {
                    binding.btnRecord.isEnabled = true
                    binding.btnFreeze.isEnabled = true
                    binding.btnRecord.text = getString(R.string.stop_listening)
                    binding.tvStatus.text = getString(
                        R.string.state_buffering,
                        "%.1f".format(profile.expectedFirstResultMs / 1000.0)
                    )
                    binding.ivHeatmapOverlay.visibility = View.VISIBLE
                    binding.ivHeatmapOverlay.alpha = 0f
                    hasFirstResult = false
                    startBufferHairline()
                }
                is UiState.Error -> {
                    stopLiveStreaming()
                    binding.tvStatus.text = describeError(state.message)
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
            if (profile.isPixel) applyPixelOverlayMatrix(bitmap.width, bitmap.height)
            binding.tvGate.visibility = View.GONE
            if (!hasFirstResult) {
                // The one orchestrated moment: the veil arrives.
                hasFirstResult = true
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.state_live)
                if (reducedMotion()) {
                    binding.ivHeatmapOverlay.alpha = 0.75f
                } else {
                    binding.ivHeatmapOverlay.animate().alpha(0.75f).setDuration(300).start()
                }
            }
        }

        viewModel.playbackMode.observe(this) { mode ->
            applyPlaybackMode(mode)
            val checkedId = when (mode) {
                PlaybackMode.BOTH -> binding.btnSoloBoth.id
                PlaybackMode.LEFT_ONLY -> binding.btnSoloFirst.id
                PlaybackMode.RIGHT_ONLY -> binding.btnSoloSecond.id
            }
            if (binding.soloGroup.checkedButtonId != checkedId) {
                binding.soloGroup.check(checkedId)
            }
        }

        viewModel.frozenLive.observe(this) { f ->
            binding.btnFreeze.text = getString(if (f) R.string.unfreeze else R.string.freeze)
            binding.tvFrozen.visibility =
                if (f && profile.isPixel) View.VISIBLE else View.GONE
        }

        viewModel.following.observe(this) { f ->
            binding.tvFollowing.visibility =
                if (f && profile.isPixel) View.VISIBLE else View.GONE
            if (!f) binding.gridLattice.clearStickyCell()
        }
        binding.tvFollowing.setOnClickListener {
            viewModel.soloSource(null)  // clears both a dot solo and a held-cell follow
        }

        viewModel.noLocalization.observe(this) { gated ->
            if (gated) {
                // Honest state: the model is not confident about WHERE the
                // sound is. Clear the overlay instead of showing garbage;
                // separated audio keeps playing.
                binding.ivHeatmapOverlay.setImageDrawable(null)
                binding.tvGate.visibility = View.VISIBLE
            } else {
                binding.tvGate.visibility = View.GONE
            }
        }
    }

    /** Fill the hairline over the model's expected first-result time, so
     *  the buffering wait is honest, not indeterminate. */
    private fun startBufferHairline() {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        if (reducedMotion()) {
            // No animation: leave a static, truthful track instead.
            binding.progressBar.progress = 500
            return
        }
        android.animation.ObjectAnimator.ofInt(binding.progressBar, "progress", 0, 1000)
            .setDuration(profile.expectedFirstResultMs)
            .start()
    }

    /** Map raw stream errors to copy that says what to do next. Seam S6:
     *  the substring classification is pure (classifyStreamError, MU-403);
     *  only the localized copy lives here. */
    private fun describeError(raw: String?): String {
        val msg = raw ?: ""
        return when (com.k1llerwhale.sonicsight.util.classifyStreamError(raw)) {
            com.k1llerwhale.sonicsight.util.StreamErrorKind.MODEL_UNAVAILABLE ->
                getString(R.string.error_model_unavailable, profile.displayName)
            com.k1llerwhale.sonicsight.util.StreamErrorKind.SERVER_UNREACHABLE ->
                getString(R.string.error_server_unreachable, GrpcModule.currentHost())
            com.k1llerwhale.sonicsight.util.StreamErrorKind.GENERIC ->
                getString(R.string.error_generic, msg.ifEmpty { "unknown error" })
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

        // Headphones change the experience substantially — said once, then
        // never again (a hint, not a nag).
        val prefs = getSharedPreferences("sonicsight_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("hint_headphones_shown", false)) {
            prefs.edit().putBoolean("hint_headphones_shown", true).apply()
            binding.tvStatus.postDelayed(
                { binding.tvStatus.text = getString(R.string.hint_headphones) }, 600
            )
        }

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
            // sonicsight, ~33 ms = 30 fps for multisensory). Seam S6: the
            // gate arithmetic lives in ThrottleGate (JVM-tested, MU-506).
            val throttleGate = com.k1llerwhale.sonicsight.util.ThrottleGate(
                profile.frameIntervalMs
            ) { SystemClock.elapsedRealtime() }
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                perfFramesArrived++
                if (throttleGate.tryPass()) {
                    processImageProxy(imageProxy)
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
        lastFrameW = bitmap.width
        lastFrameH = bitmap.height

        // 3. Prepare frame(s) per the model profile and compress
        val chunk: StreamChunk = if (profile.frameKind == FrameKind.FULL_LETTERBOXED) {
            val fullJpeg = com.k1llerwhale.sonicsight.util.ImageTransform.letterboxAndCompress(bitmap)
            StreamChunk.newBuilder()
                .setTimestampMs(timestampMs)
                .setFullJpeg(ByteString.copyFrom(fullJpeg))
                .setFrameWidth(224)
                .setFrameHeight(224)
                // Touch-mode level flags ride the 8 fps frame chunks —
                // well inside the server's ~1 s request_clusters latch.
                .setFreeze(profile.isPixel && viewModel.frozen)
                .setRequestClusters(profile.isPixel)
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

        // Which proto field carries the audio is a question of RATE, not frame
        // kind: touch mode sends full frames but 11025 Hz audio_pcm, only the
        // 22050 Hz multisensory branch uses audio_pcm_hi.
        val isHiRate = profile.streamRate > 11025
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
                            if (profile.isPixel) {
                                // Level flags on AUDIO chunks too: the proto
                                // asks for the flag on every chunk, and audio
                                // chunks outnumber frames (review finding —
                                // without this the server's freeze flapped).
                                builder.setFreeze(viewModel.frozen)
                                builder.setRequestClusters(true)
                            }

                            // Lossless, suspending: audio chunks must never be
                            // dropped by a saturated out-queue (frames may).
                            viewModel.sendAudioChunk(builder.build())
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

        // Below this fraction of the window's mixture energy, the honest
        // answer is "no sound detected here". The wire value is RELATIVE
        // (0..1), so this is scale-free; still provisional until measured
        // on real scenes (C2 spirit).
        private const val QUERY_SILENCE_ENERGY = 0.02f
    }
}
