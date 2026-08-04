package com.k1llerwhale.sonicsight.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ImageTransform {

    /**
     * Converts a CameraX ImageProxy (YUV_420_888) into an ARGB Bitmap.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Scales the smaller edge to 256, maintaining aspect ratio.
     */
    private fun scaleSmallestEdgeTo256(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val scaledWidth: Int
        val scaledHeight: Int

        if (width < height) {
            scaledWidth = 256
            scaledHeight = (256.0f / width * height).toInt()
        } else {
            scaledHeight = 256
            scaledWidth = (256.0f / height * width).toInt()
        }

        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    /**
     * Center crops a highly-specific 224x224 chunk from the scaled bitmap.
     */
    private fun centerCrop224(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val startX = (width - 224) / 2
        val startY = (height - 224) / 2

        val cropped = Bitmap.createBitmap(bitmap, startX, startY, 224, 224)
        if (cropped != bitmap) {
            bitmap.recycle()
        }
        return cropped
    }

    /**
     * Extracts the left/right halves, normalizes coordinates, and outputs two JPEG byte arrays.
     */
    fun processAndCompressHalves(bitmap: Bitmap): Pair<ByteArray, ByteArray> {
        val width = bitmap.width
        val height = bitmap.height
        val halfWidth = width / 2

        // 1. Split down the middle
        val leftHalf = Bitmap.createBitmap(bitmap, 0, 0, halfWidth, height)
        val rightHalf = Bitmap.createBitmap(bitmap, halfWidth, 0, halfWidth, height)

        // 2. Scale exactly like build_vid_transform_eval (Resize shortest edge to 256)
        val leftScaled = scaleSmallestEdgeTo256(leftHalf)
        val rightScaled = scaleSmallestEdgeTo256(rightHalf)

        // 3. Center crop to 224x224
        val leftCropped = centerCrop224(leftScaled)
        val rightCropped = centerCrop224(rightScaled)

        // 4. Compress to transient JPEGs
        val leftOut = ByteArrayOutputStream()
        leftCropped.compress(Bitmap.CompressFormat.JPEG, 90, leftOut)
        val rightOut = ByteArrayOutputStream()
        rightCropped.compress(Bitmap.CompressFormat.JPEG, 90, rightOut)

        // 5. Explicitly trigger memory reclamation to avoid out-of-memory exception
        leftCropped.recycle()
        rightCropped.recycle()

        return Pair(leftOut.toByteArray(), rightOut.toByteArray())
    }

    /**
     * Letterboxes the full frame into a dim x dim JPEG for the multisensory
     * branch: the frame is fit inside the square preserving aspect ratio and
     * padded with mid-grey (1280x720 -> 224x126 content with 49 grey rows
     * top and bottom; portrait frames get grey columns instead).
     */
    fun letterboxAndCompress(bitmap: Bitmap, dim: Int = 224, quality: Int = 90): ByteArray {
        val scale = dim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).roundToInt().coerceIn(1, dim)
        val h = (bitmap.height * scale).roundToInt().coerceIn(1, dim)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val boxed = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(boxed)
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(scaled, (dim - w) / 2f, (dim - h) / 2f, null)
        if (scaled != bitmap) {
            scaled.recycle()
        }

        val out = ByteArrayOutputStream()
        boxed.compress(Bitmap.CompressFormat.JPEG, quality, out)
        boxed.recycle()
        return out.toByteArray()
    }
}
