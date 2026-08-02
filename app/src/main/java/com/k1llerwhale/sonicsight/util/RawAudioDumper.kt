package com.k1llerwhale.sonicsight.util

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DIAGNOSTIC ONLY.
 *
 * Writes PCM16-LE mono audio to a WAV file on device storage, patching the
 * RIFF header on close. Used to capture the exact bytes handed to the gRPC
 * stream so they can be compared byte-for-byte against the server-side
 * mic.wav from the same session.
 *
 * Output location (no runtime permission required):
 *   Android/data/com.k1llerwhale.sonicsight/files/sonicsight/mic_raw_<ts>.wav
 */
class RawAudioDumper(outFile: File, private val sampleRate: Int) {

    private val raf = RandomAccessFile(outFile, "rw")
    private var dataBytes = 0
    private var closed = false

    val path: String = outFile.absolutePath

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))   // placeholder, patched in close()
    }

    @Synchronized
    fun write(buf: ByteArray, len: Int) {
        if (closed || len <= 0) return
        try {
            raf.write(buf, 0, len)
            dataBytes += len
        } catch (e: Exception) {
            Log.e(TAG, "write failed: ${e.message}")
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.close()
            Log.i(TAG, "wrote $dataBytes bytes (${dataBytes / 2} samples, " +
                    "${"%.2f".format(dataBytes / 2.0 / sampleRate)} s) -> $path")
        } catch (e: Exception) {
            Log.e(TAG, "close failed: ${e.message}")
        }
    }

    private fun header(dataLen: Int): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + dataLen)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)                 // PCM fmt chunk size
        b.putShort(1)                // PCM
        b.putShort(1)                // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)     // byte rate = rate * channels * 2
        b.putShort(2)                // block align
        b.putShort(16)               // bits per sample
        b.put("data".toByteArray())
        b.putInt(dataLen)
        return b.array()
    }

    private companion object {
        const val TAG = "SonicSightDump"
        const val HEADER_SIZE = 44
    }
}
