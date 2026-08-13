package com.k1llerwhale.sonicsight.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MU-503 / MU-505 / MU-508 / MU-602 — bitmap-backed frame-pipeline tests
 * under single-SDK Robolectric (native graphics). This is a unit-test
 * enabler, NOT the deferred Tier 0 multi-API matrix; SDK level pinned via
 * @Config and recorded in the report.
 *
 * MU-508 oracle: [referenceYuvToRgb] — a stride- and pixelStride-honouring
 * BT.601 full-range conversion written from the YUV_420_888 spec,
 * independent of ImageTransform.imageProxyToBitmap. Tolerances are the
 * MI-DEV-002 derivation (max 16 / mean 2 per channel); structural
 * corruption asserts mean > 8 (far outside legitimate JPEG noise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FramePipelineRobolectricTest {

    // ── MU-503 · rotation correctness through the halves chain ──────────

    private fun quadrantBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val c = when {
                x < w / 2 && y < h / 2 -> Color.RED
                x >= w / 2 && y < h / 2 -> Color.GREEN
                x < w / 2 && y >= h / 2 -> Color.BLUE
                else -> Color.YELLOW
            }
            bmp.setPixel(x, y, c)
        }
        return bmp
    }

    /** Pure pixel-loop rotation: Robolectric's Canvas/Matrix composition
     *  silently no-ops here (observed: black output), so the test-side
     *  input rotation avoids Canvas entirely. The APP's own rotation path
     *  is exercised on hardware (MI-DEV-002). */
    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val w = bmp.width; val h = bmp.height
        val out = when (degrees) {
            90, 270 -> Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
            else -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val p = bmp.getPixel(x, y)
            when (degrees) {
                90 -> out.setPixel(h - 1 - y, x, p)
                180 -> out.setPixel(w - 1 - x, h - 1 - y, p)
                270 -> out.setPixel(y, w - 1 - x, p)
            }
        }
        return out
    }

    private fun dominantCorner(jpeg: ByteArray, left: Boolean, top: Boolean): Int {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!
        val x = if (left) 24 else bmp.width - 24
        val y = if (top) 24 else bmp.height - 24
        return bmp.getPixel(x, y)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(
            "expected ~${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) < 60 &&
                abs(Color.green(expected) - Color.green(actual)) < 60 &&
                abs(Color.blue(expected) - Color.blue(actual)) < 60
        )
    }

    @Test
    fun mu503_rotation0_leftHalfIsRedBlue() {
        val (leftJpeg, rightJpeg) = ImageTransform.processAndCompressHalves(quadrantBitmap(1280, 720))
        assertColorNear(Color.RED, dominantCorner(leftJpeg, left = true, top = true))
        assertColorNear(Color.BLUE, dominantCorner(leftJpeg, left = true, top = false))
        assertColorNear(Color.GREEN, dominantCorner(rightJpeg, left = false, top = true))
        assertColorNear(Color.YELLOW, dominantCorner(rightJpeg, left = false, top = false))
    }

    @Test
    fun mu503_rotation180_halvesSwapAndFlip() {
        val rotated = rotate(quadrantBitmap(1280, 720), 180)
        val (leftJpeg, _) = ImageTransform.processAndCompressHalves(rotated)
        // After 180: original bottom-right (YELLOW) is now top-left.
        assertColorNear(Color.YELLOW, dominantCorner(leftJpeg, left = true, top = true))
        assertColorNear(Color.GREEN, dominantCorner(leftJpeg, left = true, top = false))
    }

    @Test
    fun mu503_rotation90_portraitGeometryHoldsD2Crop() {
        // 90-degree rotation makes the frame portrait 720x1280: halves are
        // 360x1280 -> 256x910 -> crop 224 rows starting at 343 (D2).
        val rotated = rotate(quadrantBitmap(1280, 720), 90)
        assertEquals(720, rotated.width)
        assertEquals(1280, rotated.height)
        val (leftJpeg, rightJpeg) = ImageTransform.processAndCompressHalves(rotated)
        val left = BitmapFactory.decodeByteArray(leftJpeg, 0, leftJpeg.size)!!
        assertEquals(224, left.width)
        assertEquals(224, left.height)
        // After 90 CW the portrait quadrants are TL=BLUE (source BL),
        // TR=RED (source TL), BL=YELLOW, BR=GREEN. The D2 crop band
        // (rows 343..566 of 910) maps back to portrait rows ~482..797,
        // so each half's crop spans the colour boundary at row 640:
        // upper crop region shows the top colour of that half.
        assertColorNear(Color.BLUE, dominantCorner(leftJpeg, left = true, top = true))
        assertColorNear(Color.RED, dominantCorner(rightJpeg, left = false, top = true))
    }

    // ── MU-505 · wire JPEG decodes at 224x224; size observed ────────────

    @Test
    fun mu505_halvesOutputsDecodeTo224Square() {
        val (l, r) = ImageTransform.processAndCompressHalves(quadrantBitmap(1280, 720))
        for (jpeg in listOf(l, r)) {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!
            assertEquals(224, bmp.width)
            assertEquals(224, bmp.height)
        }
        println("MU-505 observation: halves JPEG sizes = ${l.size} / ${r.size} bytes")
    }

    @Test
    fun mu505_letterboxOutputDecodesTo224Square() {
        // Bar/content COLOUR is not assertable here: letterboxAndCompress
        // paints via Canvas, which no-ops under this Robolectric build —
        // colour validation is MI-DEV-002's job on hardware. Geometry is
        // pinned in MU-504 (pure math); this pins the encoded frame shape.
        val jpeg = ImageTransform.letterboxAndCompress(quadrantBitmap(1280, 720))
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)!!
        assertEquals(224, bmp.width)
        assertEquals(224, bmp.height)
        println("MU-505 observation: letterbox JPEG size = ${jpeg.size} bytes")
    }

    // ── MU-602 · render dimensions (REC-2) ──────────────────────────────

    @Test
    fun mu602_transparentHeatmapRendersFixed336Square() {
        val mp = MaskProcessor()
        val fiftySix = mp.createTransparentHeatmap(ByteArray(3136) { 40 })!!
        assertEquals(336, fiftySix.width)
        assertEquals(336, fiftySix.height)
        // Any square side still renders the fixed 336 target (REC-2).
        val fourteen = mp.createTransparentHeatmap(ByteArray(196) { 40 })!!
        assertEquals(336, fourteen.width)
        assertEquals(336, fourteen.height)
    }

    @Test
    fun mu602_halvesPairStitchesTo672x336() {
        val mp = MaskProcessor()
        val l = mp.createTransparentHeatmap(ByteArray(3136) { 40 })!!
        val r = mp.createTransparentHeatmap(ByteArray(3136) { 80 })!!
        val stitched = mp.stitchSideBySide(l, r)
        assertEquals(672, stitched.width)
        assertEquals(336, stitched.height)
    }

    @Test
    fun mu602_pixelOverlayRendersGridTimes24() {
        val mp = MaskProcessor()
        val overlay = mp.createPixelOverlay(ByteArray(196) { 128.toByte() }, 14, 14)!!
        assertEquals(336, overlay.width)   // 14 * 24
        assertEquals(336, overlay.height)
    }

    // ── MU-508 · stride matrix vs independent reference ─────────────────

    /** Deterministic YUV source: gradients over the full plane. */
    private class YuvFrame(val width: Int, val height: Int) {
        val y = ByteArray(width * height) { i ->
            val r = i / width; val c = i % width
            (((r * 7 + c * 3) % 220) + 18).toByte()
        }
        val u = ByteArray((width / 2) * (height / 2)) { i ->
            val r = i / (width / 2); val c = i % (width / 2)
            (((r * 5 + c * 11) % 200) + 28).toByte()
        }
        val v = ByteArray((width / 2) * (height / 2)) { i ->
            val r = i / (width / 2); val c = i % (width / 2)
            (((r * 13 + c * 2) % 200) + 28).toByte()
        }
    }

    private class FakePlane(
        private val data: ByteArray,
        private val rs: Int,
        private val ps: Int,
    ) : ImageProxy.PlaneProxy {
        override fun getRowStride() = rs
        override fun getPixelStride() = ps
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(data)
    }

    private class FakeImageProxy(
        private val w: Int,
        private val h: Int,
        private val planeArray: Array<ImageProxy.PlaneProxy>,
    ) : ImageProxy {
        override fun close() {}
        override fun getCropRect() = android.graphics.Rect(0, 0, w, h)
        override fun setCropRect(rect: android.graphics.Rect?) {}
        override fun getFormat() = android.graphics.ImageFormat.YUV_420_888
        override fun getHeight() = h
        override fun getWidth() = w
        override fun getImage() = null
        override fun getPlanes() = planeArray
        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("unused")
    }

    /** Semi-planar (NV21-underlying) planes: the layout the app's packed
     *  assumption happens to survive. */
    private fun semiPlanarProxy(f: YuvFrame): FakeImageProxy {
        val vu = ByteArray(f.u.size + f.v.size)
        for (i in f.v.indices) { vu[2 * i] = f.v[i]; vu[2 * i + 1] = f.u[i] }
        val uView = ByteBuffer.wrap(vu, 1, vu.size - 1).slice()
        val vView = ByteBuffer.wrap(vu, 0, vu.size).slice()
        return FakeImageProxy(f.width, f.height, arrayOf(
            FakePlane(f.y, f.width, 1),
            object : ImageProxy.PlaneProxy {
                override fun getRowStride() = f.width
                override fun getPixelStride() = 2
                override fun getBuffer() = uView.duplicate()
            },
            object : ImageProxy.PlaneProxy {
                override fun getRowStride() = f.width
                override fun getPixelStride() = 2
                override fun getBuffer() = vView.duplicate()
            },
        ))
    }

    /** Fully planar layout (pixelStride 1): breaks the packed assumption. */
    private fun planarProxy(f: YuvFrame): FakeImageProxy = FakeImageProxy(
        f.width, f.height, arrayOf(
            FakePlane(f.y, f.width, 1),
            FakePlane(f.u, f.width / 2, 1),
            FakePlane(f.v, f.width / 2, 1),
        ))

    /** Row-padded Y plane (rowStride > width): breaks the packed assumption. */
    private fun paddedProxy(f: YuvFrame, pad: Int): FakeImageProxy {
        val stride = f.width + pad
        val padded = ByteArray(stride * f.height)
        for (r in 0 until f.height) {
            System.arraycopy(f.y, r * f.width, padded, r * stride, f.width)
        }
        val vu = ByteArray(f.u.size + f.v.size)
        for (i in f.v.indices) { vu[2 * i] = f.v[i]; vu[2 * i + 1] = f.u[i] }
        return FakeImageProxy(f.width, f.height, arrayOf(
            FakePlane(padded, stride, 1),
            FakePlane(vu.copyOfRange(1, vu.size), f.width, 2),
            FakePlane(vu, f.width, 2),
        ))
    }

    /** Independent oracle: stride-honouring BT.601 full-range conversion. */
    private fun referenceYuvToRgb(f: YuvFrame): Bitmap {
        val bmp = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        for (row in 0 until f.height) for (col in 0 until f.width) {
            val yv = (f.y[row * f.width + col].toInt() and 0xFF).toDouble()
            val ci = (row / 2) * (f.width / 2) + (col / 2)
            val uv = (f.u[ci].toInt() and 0xFF) - 128.0
            val vv = (f.v[ci].toInt() and 0xFF) - 128.0
            val r = (yv + 1.402 * vv).roundToInt().coerceIn(0, 255)
            val g = (yv - 0.344136 * uv - 0.714136 * vv).roundToInt().coerceIn(0, 255)
            val b = (yv + 1.772 * uv).roundToInt().coerceIn(0, 255)
            bmp.setPixel(col, row, Color.rgb(r, g, b))
        }
        return bmp
    }

    private data class Diff(val mean: Double, val max: Int)

    private fun diff(a: Bitmap, b: Bitmap): Diff {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var sum = 0L; var max = 0; var n = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((pa shr shift) and 0xFF) - ((pb shr shift) and 0xFF))
                sum += d; if (d > max) max = d; n++
            }
        }
        return Diff(sum.toDouble() / n, max)
    }

    @org.junit.Ignore(
        "MU-508: Robolectric's YuvImage.compressToJpeg emits a placeholder " +
        "(decodes as 100x100), so imageProxyToBitmap cannot run its real " +
        "encoder off-device. Stride-matrix confirmation moves to MI-DEV-002 " +
        "on hardware; the reference converter below is that probe's oracle.")
    @Test
    fun mu508_semiPlanarPackedLayoutMatchesReference() {
        val f = YuvFrame(128, 96)
        val app = ImageTransform.imageProxyToBitmap(semiPlanarProxy(f))!!
        val d = diff(app, referenceYuvToRgb(f))
        println("MU-508 observation: semi-planar mean=%.2f max=%d".format(d.mean, d.max))
        assertTrue("mean ${d.mean} above derived tolerance 2", d.mean <= 2.0)
        assertTrue("max ${d.max} above derived tolerance 16", d.max <= 16)
    }

    @org.junit.Ignore("MU-508: see mu508_semiPlanarPackedLayoutMatchesReference — YuvImage placeholder under Robolectric")
    @Test
    fun mu508_planarLayoutDiverges_expectedDefect() {
        // EXPECTED failure of the conversion (pinned as a defect, not a
        // test failure): pixelStride-1 planar input violates the packed
        // NV21 assumption in imageProxyToBitmap — chroma order breaks.
        val f = YuvFrame(128, 96)
        val app = ImageTransform.imageProxyToBitmap(planarProxy(f))!!
        val d = diff(app, referenceYuvToRgb(f))
        println("MU-508 observation: planar mean=%.2f max=%d".format(d.mean, d.max))
        assertTrue("planar layout should corrupt (mean ${d.mean} <= 8 would mean it works!)",
            d.mean > 8.0)
    }

    @org.junit.Ignore("MU-508: see mu508_semiPlanarPackedLayoutMatchesReference — YuvImage placeholder under Robolectric")
    @Test
    fun mu508_paddedRowStrideDiverges_expectedDefect() {
        val f = YuvFrame(128, 96)
        val app = ImageTransform.imageProxyToBitmap(paddedProxy(f, pad = 64))
        if (app == null) {
            // Corruption may also surface as a decode failure — equally a
            // divergence from the reference (which always converts).
            return
        }
        val d = diff(app, referenceYuvToRgb(f))
        println("MU-508 observation: padded mean=%.2f max=%d".format(d.mean, d.max))
        assertTrue("padded rowStride should corrupt (mean ${d.mean})", d.mean > 8.0)
    }
}
