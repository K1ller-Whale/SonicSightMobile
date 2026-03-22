package com.k1llerwhale.sonicsight.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MaskProcessor {
    /**
     * Creates an overlay bitmap by combining a heatmap and a center frame.
     *
     * @param heatmapBytes Raw float32 bytes of the 224x224 heatmap
     * @param frameJpeg JPEG-encoded center frame
     * @param alpha Transparency of the heatmap overlay (0.0 to 1.0)
     * @return A Bitmap with the heatmap overlaid on the frame
     */
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
            // Use 1.0 - v because the model typically returns high values for sources,
            // and we want those to be red.
            pixels[i] = jetColormap(1.0f - v)
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
     * Creates a transparent heatmap bitmap that can be overlaid on the live camera preview.
     * Alpha is proportional to the intensity of the sound at that pixel.
     */
    fun createTransparentHeatmap(
        heatmapBytes: ByteArray,
        width: Int = 24,
        height: Int = 24
    ): Bitmap? {
        try {
            val heatmap = FloatArray(width * height)
            for (i in 0 until (width * height)) {
                if (i < heatmapBytes.size) {
                    // For the Stream process, the backend encodes it as a byte array from uint8 (0-255)
                    heatmap[i] = (heatmapBytes[i].toInt() and 0xFF) / 255.0f
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            for (i in 0 until (width * height)) {
                val v = heatmap[i].coerceIn(0f, 1f)
                // The model outputs 0 for silence, 1 for sound.
                // We want the heatmap to be universally visible (like a thermal camera overlay),
                // so we give it a base transparency of ~30%, and scale up to 80% for sound.
                val alpha = (75 + (v * 130)).toInt().coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (jetColormap(1.0f - v) and 0x00FFFFFF)
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            // Explicitly scale up the 24x24 bitmap to a robust texture size (e.g. 240x240)
            // This prevents Android's hardware accelerator from failing/blurring tiny textures
            // when mapping them to large device screens using 'fitXY'
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 240, 240, true)
            bitmap.recycle() // CRITICAL: Prevent Memory Leak
            return scaledBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun jetColormap(v: Float): Int {
        val r = (clamp(1.5f - Math.abs(v - 0.75f) * 4f) * 255).toInt()
        val g = (clamp(1.5f - Math.abs(v - 0.5f) * 4f) * 255).toInt()
        val b = (clamp(1.5f - Math.abs(v - 0.25f) * 4f) * 255).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Float) = Math.min(1f, Math.max(0f, v))
}
