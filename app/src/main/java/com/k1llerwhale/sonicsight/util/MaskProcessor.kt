package com.k1llerwhale.sonicsight.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class MaskProcessor {
    /**
     * Creates an overlay bitmap by combining a float32 heatmap and a center frame.
     * Used for full video processing (non-streaming).
     */
    fun createOverlay(
        heatmapBytes: ByteArray,
        frameJpeg: ByteArray,
        alpha: Float = 0.6f
    ): Bitmap? {
        try {
            // 1. Decode heatmap from float32 bytes (Little Endian)
            val buffer = ByteBuffer.wrap(heatmapBytes).order(ByteOrder.LITTLE_ENDIAN)
            val heatmap = FloatArray(224 * 224)
            if (heatmapBytes.size >= heatmap.size * 4) {
                buffer.asFloatBuffer().get(heatmap)
            } else {
                return null
            }

            // 2. Decode center frame
            val frame = BitmapFactory.decodeByteArray(frameJpeg, 0, frameJpeg.size) ?: return null

            // 3. Create heatmap bitmap
            val heatmapBitmap = createHeatmapBitmap(heatmap, 224, 224)
            val scaledHeatmap = Bitmap.createScaledBitmap(
                heatmapBitmap, frame.width, frame.height, true
            )

            // 4. Alpha blend
            val result = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(frame, 0f, 0f, null)

            val paint = Paint().apply {
                this.alpha = (alpha * 255).toInt()
                this.isFilterBitmap = true
            }
            canvas.drawBitmap(scaledHeatmap, 0f, 0f, paint)

            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun createHeatmapBitmap(values: FloatArray, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        for (i in 0 until (w * h)) {
            val v = values[i].coerceIn(0f, 1f)
            pixels[i] = heatColor(v)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * Stitches two bitmaps side-by-side.
     */
    fun stitchSideBySide(left: Bitmap, right: Bitmap): Bitmap {
        val combinedWidth = left.width + right.width
        val maxHeight = Math.max(left.height, right.height)

        val result = Bitmap.createBitmap(combinedWidth, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(left, 0f, 0f, null)
        canvas.drawBitmap(right, left.width.toFloat(), 0f, null)

        return result
    }

    /**
     * Creates a transparent heatmap bitmap overlaid on the live camera preview.
     *
     * The backend now sends a 56x56 uint8 heatmap (was 24x24). Both sizes are
     * derived from the same mean-reduced mask, so the default parameters match
     * the current server output. The call sites do not need to change because
     * width/height are read from the proto payload via heatmapBytes.size.
     *
     * Alpha rendering uses a power-2.5 gamma curve so only genuinely high
     * activations become visible. Low-activation pixels (background, silence)
     * fade to transparent rather than accumulating as a red haze. This gives
     * the same localization as the /predict endpoint overlay while keeping the
     * background clear instead of blue.
     */
    fun createTransparentHeatmap(
        heatmapBytes: ByteArray,
        width: Int = 56,
        height: Int = 56
    ): Bitmap? {
        try {
            // Infer actual grid size from the byte count so the function works
            // with any resolution the server sends (56x56 = 3136 bytes currently).
            val totalPixels = heatmapBytes.size.coerceAtLeast(1)
            val inferredSide = Math.sqrt(totalPixels.toDouble()).toInt().coerceAtLeast(1)
            val w = if (inferredSide * inferredSide == totalPixels) inferredSide else width
            val h = if (inferredSide * inferredSide == totalPixels) inferredSide else height
            val pixelCount = w * h

            val heatmap = FloatArray(pixelCount)
            for (i in 0 until pixelCount) {
                if (i < heatmapBytes.size) {
                    // Backend encodes as uint8 (0-255)
                    heatmap[i] = (heatmapBytes[i].toInt() and 0xFF) / 255.0f
                }
            }

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(pixelCount)

            for (i in 0 until pixelCount) {
                val v = heatmap[i].coerceIn(0f, 1f)
                // Gamma-2.5 curve: only high activations (genuine sound sources)
                // become visible. Low values (background) are nearly transparent,
                // avoiding the red haze that the old linear alpha produced.
                // v=1.0 -> alpha=200, v=0.5 -> alpha=~18, v=0.3 -> alpha=~4
                val alphaf = v.toDouble().pow(2.5) * 200.0
                val alpha = alphaf.toInt().coerceIn(0, 200)
                pixels[i] = (alpha shl 24) or (heatColor(v) and 0x00FFFFFF)
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            // Scale to a robust texture size so Android's hardware accelerator
            // maps it cleanly to the full preview surface.
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 336, 336, true)
            bitmap.recycle()
            return scaledBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Heatmap colours come from the magma spectrogram palette: perceptually
    // uniform, monotonic in lightness (so the scale survives red-green
    // colour-vision deficiencies), dark at the quiet end so it vanishes
    // over the camera image. Replaces the old jet map.
    private fun heatColor(v: Float): Int = MagmaPalette.color(v)
}
