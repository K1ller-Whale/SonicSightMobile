package com.k1llerwhale.sonicsight.presentation.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.k1llerwhale.sonicsight.databinding.ActivityResultBinding
import java.io.File

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var exoPlayer: ExoPlayer? = null
    private var mediaPlayer: MediaPlayer? = null

    // File Paths
    private var rawVideoPath: String? = null
    private var processedVideoPath: String? = null
    private var heatmapPath: String? = null
    private var audio1Path: String? = null
    private var audio2Path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Unpack Intent Data
        intent.extras?.let {
            rawVideoPath = it.getString(ARG_RAW_VIDEO)
            processedVideoPath = it.getString(ARG_PROCESSED_VIDEO)
            heatmapPath = it.getString(ARG_HEATMAP)
            audio1Path = it.getString(ARG_AUDIO_1)
            audio2Path = it.getString(ARG_AUDIO_2)
        }

        setupHeatmap()
        setupAudioPlayers()
        setupVideoPlayer()
    }

    private fun setupHeatmap() {
        heatmapPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                // PhotoView handles zooming automatically
                binding.imgHeatmap.setImageBitmap(bitmap)
            }
        }
    }

    private fun setupAudioPlayers() {
        binding.btnPlaySource1.setOnClickListener { playAudio(audio1Path) }
        binding.btnPlaySource2.setOnClickListener { playAudio(audio2Path) }
    }

    private fun playAudio(path: String?) {
        if (path == null) return

        // Stop previous
        mediaPlayer?.release()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
            }
            Toast.makeText(this, "Playing Audio...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupVideoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.videoPlayer.player = exoPlayer

        // Default to Raw Video
        playVideo(rawVideoPath)

        binding.toggleVideoType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.btnRawVideo.id -> playVideo(rawVideoPath)
                    binding.btnProcessedVideo.id -> playVideo(processedVideoPath)
                }
            }
        }
    }

    private fun playVideo(path: String?) {
        path?.let {
            val mediaItem = MediaItem.fromUri(it)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        mediaPlayer?.release()
    }

    companion object {
        private const val ARG_RAW_VIDEO = "raw_video"
        private const val ARG_PROCESSED_VIDEO = "processed_video"
        private const val ARG_HEATMAP = "heatmap"
        private const val ARG_AUDIO_1 = "audio1"
        private const val ARG_AUDIO_2 = "audio2"

        fun start(
            context: Context,
            rawVideo: File,
            processedVideo: File,
            heatmap: File,
            audio1: File,
            audio2: File
        ) {
            val intent = Intent(context, ResultActivity::class.java).apply {
                putExtra(ARG_RAW_VIDEO, rawVideo.absolutePath)
                putExtra(ARG_PROCESSED_VIDEO, processedVideo.absolutePath)
                putExtra(ARG_HEATMAP, heatmap.absolutePath)
                putExtra(ARG_AUDIO_1, audio1.absolutePath)
                putExtra(ARG_AUDIO_2, audio2.absolutePath)
            }
            context.startActivity(intent)
        }
    }
}