package com.k1llerwhale.sonicsight.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

object MediaUtils {

    private const val TAG = "MediaUtils"

    /**
     * Copies a video file from the app's private cache to the device's public Gallery.
     * Use this to inspect the output.
     */
    fun saveVideoToGallery(context: Context, videoFile: File, prefix: String) {
        if (!videoFile.exists()) {
            Log.e(TAG, "File not found: ${videoFile.absolutePath}")
            return
        }

        val timestamp = System.currentTimeMillis()
        val filename = "${prefix}_${timestamp}.mp4"

        // Prepare details for the MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // This creates a specific folder "SonicSight" in your Movies directory
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SonicSight")
        }

        val contentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        try {
            uri?.let { outputUri ->
                // Copy data from Cache File -> Gallery File
                contentResolver.openOutputStream(outputUri).use { outputStream ->
                    FileInputStream(videoFile).use { inputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                }
                Log.d(TAG, "Saved to Gallery: $filename")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery", e)
        }
    }
    fun saveBase64ToFile(context: Context, base64String: String, fileName: String): File? {
        return try {
            val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
            val file = File(context.cacheDir, fileName)
            java.io.FileOutputStream(file).use { it.write(decodedBytes) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode/save file: $fileName", e)
            null
        }
    }
}