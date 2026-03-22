package com.k1llerwhale.sonicsight.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

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
}
