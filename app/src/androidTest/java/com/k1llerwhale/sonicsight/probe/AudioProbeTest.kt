package com.k1llerwhale.sonicsight.probe

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.k1llerwhale.sonicsight.util.AudioDecimator
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MI-DEV-003 — audio preprocessing determinism + capability probe
 * (mobile/mobile_test_targets.yaml MI-DEV-003). Output: one JSON per device
 * at Android/data/<pkg>/files/probe/audio_<model>_api<n>.json — pull via
 * the one-liner in docs/RUN_TESTS.md, fold into docs/DEVICE_MATRIX.md.
 *
 * Two halves, never conflated:
 *  (a) determinism — the decimator's device output must be byte-identical
 *      to the committed JVM golden (cheap insurance against a
 *      device-specific numeric path);
 *  (b) capability — evidence for WHY the app captures at 44100 Hz and
 *      decimates in software: low rates are not universally supported.
 */
@RunWith(AndroidJUnit4::class)
class AudioProbeTest {

    @get:Rule
    val audioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun outFile(): File {
        val dir = File(context.getExternalFilesDir(null), "probe").apply { mkdirs() }
        val name = "audio_${Build.MODEL.replace(' ', '_')}_api${Build.VERSION.SDK_INT}.json"
        return File(dir, name)
    }

    private fun loadGolden(name: String): IntArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("goldens/$name")
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .map { it.trim().toInt() }.toIntArray()

    // ── (a) determinism ─────────────────────────────────────────────────

    private fun impulseBytes(): ByteArray {
        val out = ByteArray(5512 * 2)
        out[0] = 0xFF.toByte(); out[1] = 0x7F   // 32767 LE at sample 0
        return out
    }

    private fun runDecimator(factor: Int, cutoff: Double): IntArray {
        val dec = AudioDecimator(factor, 121, cutoff, 44100.0)
        dec.reset()
        val input = impulseBytes()
        val out = ByteArray(dec.maxOutputBytes(input.size))
        val n = dec.process(input, input.size, out)
        val samples = IntArray(n / 2)
        for (i in samples.indices) {
            samples[i] = ((out[2 * i + 1].toInt()) shl 8) or (out[2 * i].toInt() and 0xFF)
        }
        return samples
    }

    @Test
    fun miDev003_decimatorDeterminismAgainstCommittedGoldens() {
        assertArrayEquals("halves /4 impulse response diverged on device",
            loadGolden("impulse_f4.csv"), runDecimator(4, 5000.0))
        assertArrayEquals("speech /2 impulse response diverged on device",
            loadGolden("impulse_f2.csv"), runDecimator(2, 10000.0))
    }

    // ── (b) capability matrix + effective rate ──────────────────────────

    @Test
    fun miDev003_capabilityProbe() {
        val json = JSONObject()
        json.put("probe", "MI-DEV-003")
        json.put("model", Build.MODEL)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("api", Build.VERSION.SDK_INT)
        json.put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

        // AudioRecord matrix: {44100, 22050, 11025} x {MONO, STEREO} x
        // {PCM_16BIT, FLOAT} — getMinBufferSize + does it initialize.
        val recordMatrix = JSONArray()
        for (rate in intArrayOf(44100, 22050, 11025)) {
            for ((chName, ch) in listOf(
                "MONO" to AudioFormat.CHANNEL_IN_MONO,
                "STEREO" to AudioFormat.CHANNEL_IN_STEREO)) {
                for ((fmtName, fmt) in listOf(
                    "PCM_16BIT" to AudioFormat.ENCODING_PCM_16BIT,
                    "FLOAT" to AudioFormat.ENCODING_PCM_FLOAT)) {
                    val minBuf = AudioRecord.getMinBufferSize(rate, ch, fmt)
                    var initializes = false
                    if (minBuf > 0) {
                        val rec = try {
                            AudioRecord(MediaRecorder.AudioSource.MIC, rate, ch, fmt, minBuf * 2)
                        } catch (e: Exception) { null }
                        initializes = rec?.state == AudioRecord.STATE_INITIALIZED
                        rec?.release()
                    }
                    recordMatrix.put(JSONObject()
                        .put("rate", rate).put("channels", chName).put("format", fmtName)
                        .put("minBufferSize", minBuf).put("initializes", initializes))
                }
            }
        }
        json.put("audioRecord", recordMatrix)

        // Effective delivered rate: 2 s at 44100/mono/16 — sample count over
        // elapsed time. The design's premise (44100 reliable) made visible.
        val minBuf = AudioRecord.getMinBufferSize(
            44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        assertTrue("44100/mono/16 must initialize (app's base assumption)",
            rec.state == AudioRecord.STATE_INITIALIZED)
        val buf = ByteArray(11024)
        var samples = 0L
        rec.startRecording()
        val start = SystemClock.elapsedRealtimeNanos()
        while (SystemClock.elapsedRealtimeNanos() - start < 2_000_000_000L) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) samples += n / 2
        }
        val elapsedS = (SystemClock.elapsedRealtimeNanos() - start) / 1e9
        rec.stop(); rec.release()
        json.put("effectiveCaptureRate", JSONObject()
            .put("requestedHz", 44100)
            .put("samples", samples)
            .put("elapsedSeconds", elapsedS)
            .put("effectiveHz", samples / elapsedS))

        // AudioTrack: min buffers at both wire rates + dual-track creation
        // (panned playback survival needs ears — runbook step — but dual
        // instantiation success/failure is machine-checkable).
        val trackJson = JSONArray()
        for (rate in intArrayOf(11025, 22050)) {
            val tb = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t1 = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(tb, 8192)).build()
            val t2 = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(tb, 8192)).build()
            trackJson.put(JSONObject()
                .put("rate", rate).put("minBufferSize", tb)
                .put("dualTracksInitialize",
                    t1.state == AudioTrack.STATE_INITIALIZED &&
                    t2.state == AudioTrack.STATE_INITIALIZED))
            t1.release(); t2.release()
        }
        json.put("audioTrack", trackJson)

        json.put("note", "Low input rates unsupported on some devices is WHY " +
            "the app captures at 44100 Hz and decimates in software " +
            "(AudioDecimator); this table is the evidence for that design decision.")

        outFile().writeText(json.toString(2))
    }
}
