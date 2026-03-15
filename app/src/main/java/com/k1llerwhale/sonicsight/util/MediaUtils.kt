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
            saveBytesToFile(context, decodedBytes, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode/save file: $fileName", e)
            null
        }
    }

    fun saveBytesToFile(context: Context, bytes: ByteArray, fileName: String): File? {
        return try {
            val file = File(context.cacheDir, fileName)
            java.io.FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bytes to file: $fileName", e)
            null
        }
    }

    /**
     * Wraps raw PCM data into a WAV file with a header so it can be played by MediaPlayer.
     */
    fun savePcmToWav(context: Context, pcmBytes: ByteArray, fileName: String, sampleRate: Int): File? {
        return try {
            val file = File(context.cacheDir, fileName)
            java.io.FileOutputStream(file).use { out ->
                // Write WAV Header
                writeWavHeader(out, pcmBytes.size, sampleRate)
                out.write(pcmBytes)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PCM to WAV: $fileName", e)
            null
        }
    }

    private fun writeWavHeader(out: java.io.OutputStream, pcmSize: Int, sampleRate: Int) {
        val totalDataLen = pcmSize + 36
        val byteRate = sampleRate * 2 // 16-bit mono

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // Size of fmt chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // Format (1 = PCM)
        header[21] = 0
        header[22] = 1 // Channels (1 = Mono)
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2 // Block align
        header[33] = 0
        header[34] = 16 // Bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (pcmSize and 0xff).toByte()
        header[41] = (pcmSize shr 8 and 0xff).toByte()
        header[42] = (pcmSize shr 16 and 0xff).toByte()
        header[43] = (pcmSize shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }
}