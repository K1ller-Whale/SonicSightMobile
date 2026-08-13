package com.k1llerwhale.sonicsight.probe

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.k1llerwhale.sonicsight.util.ImageTransform
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MI-DEV-002 — frame preprocessing differential oracle on real hardware
 * (mobile/mobile_test_targets.yaml MI-DEV-002; owner brief §3b).
 *
 * The oracle is [referenceYuvToRgb]: an independent, stride- and
 * pixelStride-honouring YUV_420_888 → RGB conversion. The SAME camera frame
 * goes through the app's imageProxyToBitmap and the reference; they must
 * agree within the pre-derived tolerance (max 16 / mean 2 per channel —
 * derivation in the targets YAML). Real-world scene content is irrelevant:
 * both paths see identical bytes.
 *
 * Output JSON: Android/data/<pkg>/files/probe/frame_<model>_api<n>.json.
 * §4 sequence: run THIS first on the primary demo phone; report numbers
 * before any fix decision on imageProxyToBitmap.
 */
@RunWith(AndroidJUnit4::class)
class FrameProbeTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun outFile(): File {
        val dir = File(context.getExternalFilesDir(null), "probe").apply { mkdirs() }
        return File(dir, "frame_${Build.MODEL.replace(' ', '_')}_api${Build.VERSION.SDK_INT}.json")
    }

    /** Independent stride-aware BT.601 full-range conversion (the oracle). */
    private fun referenceYuvToRgb(image: ImageProxy): Bitmap {
        val w = image.width; val h = image.height
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yv = (yBuf.get(row * yPlane.rowStride + col * yPlane.pixelStride).toInt() and 0xFF).toDouble()
                val ui = (row / 2) * uPlane.rowStride + (col / 2) * uPlane.pixelStride
                val vi = (row / 2) * vPlane.rowStride + (col / 2) * vPlane.pixelStride
                val uv = (uBuf.get(ui).toInt() and 0xFF) - 128.0
                val vv = (vBuf.get(vi).toInt() and 0xFF) - 128.0
                val r = (yv + 1.402 * vv).roundToInt().coerceIn(0, 255)
                val g = (yv - 0.344136 * uv - 0.714136 * vv).roundToInt().coerceIn(0, 255)
                val b = (yv + 1.772 * uv).roundToInt().coerceIn(0, 255)
                pixels[row * w + col] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private data class Diff(val mean: Double, val max: Int, val fracOver16: Double)

    private fun diff(a: Bitmap, b: Bitmap): Diff {
        var sum = 0L; var max = 0; var n = 0L; var over = 0L
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((pa shr shift) and 0xFF) - ((pb shr shift) and 0xFF))
                sum += d; if (d > max) max = d; if (d > 16) over++; n++
            }
        }
        return Diff(sum.toDouble() / n, max, over.toDouble() / n)
    }

    @Test
    fun miDev002_differentialOracleAndCadence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = TestLifecycleOwner()
        val executor = Executors.newSingleThreadExecutor()
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)

        val json = JSONObject()
            .put("probe", "MI-DEV-002")
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("api", Build.VERSION.SDK_INT)
        val frames = JSONArray()
        val analyzed = AtomicInteger(0)
        val cadenceTimestamps = ArrayList<Long>()
        val done = CountDownLatch(1)

        @Suppress("DEPRECATION")
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val start = SystemClock.elapsedRealtime()
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                cadenceTimestamps.add(now)
                if (analyzed.get() < 10) {
                    val idx = analyzed.incrementAndGet()
                    val frame = JSONObject()
                        .put("index", idx)
                        .put("width", image.width)
                        .put("height", image.height)
                        .put("rotationDegrees", image.imageInfo.rotationDegrees)
                    val planes = JSONArray()
                    image.planes.forEach {
                        planes.put(JSONObject()
                            .put("rowStride", it.rowStride)
                            .put("pixelStride", it.pixelStride)
                            .put("bufferSize", it.buffer.remaining()))
                    }
                    frame.put("planes", planes)

                    val appBitmap = ImageTransform.imageProxyToBitmap(image)
                    val refBitmap = referenceYuvToRgb(image)
                    if (appBitmap != null &&
                        appBitmap.width == refBitmap.width &&
                        appBitmap.height == refBitmap.height) {
                        val d = diff(appBitmap, refBitmap)
                        frame.put("diff", JSONObject()
                            .put("meanAbs", d.mean).put("maxAbs", d.max)
                            .put("fractionOver16", d.fracOver16)
                            .put("verdictGate", "PASS iff maxAbs<=16 AND meanAbs<=2 (derived pre-run)")
                            .put("verdict", if (d.max <= 16 && d.mean <= 2.0) "PASS" else "FAIL"))

                        // Rest of the real chain on the app's own output.
                        val (l, r) = ImageTransform.processAndCompressHalves(
                            appBitmap.copy(Bitmap.Config.ARGB_8888, false))
                        val lb = BitmapFactory.decodeByteArray(l, 0, l.size)
                        fun stats(bytes: ByteArray): JSONObject {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
                            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                            for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
                                val p = bmp.getPixel(x, y)
                                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
                            }
                            return JSONObject().put("jpegBytes", bytes.size)
                                .put("width", bmp.width).put("height", bmp.height)
                                .put("meanR", sr / n).put("meanG", sg / n).put("meanB", sb / n)
                        }
                        frame.put("leftCrop", stats(l)).put("rightCrop", stats(r))
                        frame.put("leftDecodes", lb != null)
                    } else {
                        frame.put("conversionFailed", appBitmap == null)
                    }
                    synchronized(frames) { frames.put(frame) }
                }
                if (now - start > 10_000) done.countDown()
            } finally {
                image.close()
            }
        }

        instrumentation.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.STARTED
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }
        assertTrue("no frames within 30 s — camera did not deliver",
            done.await(30, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            provider.unbindAll()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        executor.shutdown()

        // Delivered cadence over the 10 s window (unthrottled analyzer) +
        // what the >=125 ms gate would pass on this delivery pattern.
        val deltas = cadenceTimestamps.zipWithNext { a, b -> b - a }
        var gatePasses = 0; var last = 0L
        for (t in cadenceTimestamps) if (t - last >= 125) { gatePasses++; last = t }
        json.put("cadence", JSONObject()
            .put("framesDelivered", cadenceTimestamps.size)
            .put("windowMs", (cadenceTimestamps.lastOrNull() ?: start) - start)
            .put("meanDeltaMs", if (deltas.isEmpty()) -1.0 else deltas.average())
            .put("deliveredFps", cadenceTimestamps.size /
                (((cadenceTimestamps.lastOrNull() ?: start) - start).coerceAtLeast(1) / 1000.0))
            .put("gatePassesAt125ms", gatePasses))
        json.put("frames", frames)
        outFile().writeText(json.toString(2))
    }
}
