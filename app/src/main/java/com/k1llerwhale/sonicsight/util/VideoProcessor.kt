package com.k1llerwhale.sonicsight.util

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object VideoProcessor {

    private const val TAG = "VideoProcessor"

    fun compressVideo(inputFile: File, outputDir: File): File? {
        val outputFile = File(outputDir, "processed_output.mp4")

        if (outputFile.exists()) outputFile.delete()

        Log.d(TAG, "Starting compression on: ${inputFile.absolutePath}")

        val cmdList = ArrayList<String>()
        cmdList.add("-y")
        cmdList.add("-i")
        cmdList.add(inputFile.absolutePath)

        cmdList.add("-r"); cmdList.add("8")
        cmdList.add("-vf"); cmdList.add("scale=256:256")
        cmdList.add("-preset"); cmdList.add("ultrafast")

        // Audio Settings
        cmdList.add("-c:a"); cmdList.add("aac")
        cmdList.add("-b:a"); cmdList.add("32k")
        cmdList.add("-ac"); cmdList.add("1")
        cmdList.add("-ar"); cmdList.add("11025")

        cmdList.add(outputFile.absolutePath)

        val session = FFmpegKit.executeWithArguments(cmdList.toTypedArray())

        if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "✅ Compression Success!")

            // Inspect the file to confirm 11025Hz
            printVideoInfo(outputFile)

            return outputFile
        } else {
            Log.e(TAG, "❌ Compression Failed!")
            Log.e(TAG, "Logs: ${session.allLogsAsString}")
            return null
        }
    }

    private fun printVideoInfo(file: File) {
        Log.d(TAG, "🔍 INSPECTING FILE: ${file.name}")

        val session = FFprobeKit.getMediaInformation(file.absolutePath)
        val info = session.mediaInformation

        if (info == null) {
            Log.e(TAG, "Could not get media info.")
            return
        }

        val streams = info.streams
        for (stream in streams) {
            val props = stream.allProperties

            if (stream.type == "audio") {
                Log.i(TAG, "🎵 AUDIO STREAM FOUND:")
                Log.i(TAG, "   • Sample Rate: ${stream.sampleRate} Hz")

                // Read channels safely from JSON properties
                val channels = props?.optString("channels") ?: "Unknown"
                Log.i(TAG, "   • Channels:    $channels")

                Log.i(TAG, "   • Format:      ${stream.codec}")
            }
            if (stream.type == "video") {
                Log.i(TAG, "🎥 VIDEO STREAM FOUND:")
                Log.i(TAG, "   • Resolution:  ${stream.width}x${stream.height}")
            }
        }
    }
}