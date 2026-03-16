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
     * Creates an overlay bitmap using a pre-decoded frame bitmap and uint8 heatmap.
     * Optimized for live streaming.
     */
    fun createOverlayFromBitmap(
        heatmapBytes: ByteArray,
        frame: Bitmap,
        alpha: Float = 0.6f
    ): Bitmap? {
        if (frame.isRecycled) return null
        try {
            // 1. Decode heatmap from uint8 bytes (0-255)
            val heatmap = FloatArray(224 * 224)
            for (i in 0 until (224 * 224)) {
                if (i < heatmapBytes.size) {
                    heatmap[i] = (heatmapBytes[i].toInt() and 0xFF) / 255.0f
                }
            }

            // 2. Create heatmap bitmap
            val heatmapBitmap = createHeatmapBitmap(heatmap, 224, 224)
            val scaledHeatmap = Bitmap.createScaledBitmap(
                heatmapBitmap, frame.width, frame.height, true
            )

            // 3. Alpha blend
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

    private fun createHeatmapBitmap(values: FloatArray, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        for (i in 0 until (w * h)) {
            val v = values[i].coerceIn(0f, 1f)
            // Use 1.0 - v because the model typically returns high values for sources,
            // and we want those to be red (0.0 in JET colormap is red in some implementations,
            // but usually 1.0 is red. We'll match the backend's JET mapping logic).
            // In the backend: heatmap_norm = 1.0 - np.clip(heatmap_resized, 0.0, 1.0)
            // So we do the same here.
            pixels[i] = jetColormap(1.0f - v)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * Simple JET colormap implementation
     * Maps 0.0 -> Red, 1.0 -> Blue (matches backend logic)
     */
    private fun jetColormap(v: Float): Int {
        val r = (clamp(1.5f - Math.abs(v - 0.75f) * 4f) * 255).toInt()
        val g = (clamp(1.5f - Math.abs(v - 0.5f) * 4f) * 255).toInt()
        val b = (clamp(1.5f - Math.abs(v - 0.25f) * 4f) * 255).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Float) = Math.min(1f, Math.max(0f, v))
}
