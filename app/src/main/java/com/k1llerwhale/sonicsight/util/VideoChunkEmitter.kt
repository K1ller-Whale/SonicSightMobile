package com.k1llerwhale.sonicsight.util

import android.media.MediaMetadataRetriever
import com.google.protobuf.ByteString
import com.k1llerwhale.sonicsight.grpc.VideoChunk
import com.k1llerwhale.sonicsight.grpc.VideoMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class VideoChunkEmitter(private val videoFile: File) {
    companion object {
        const val CHUNK_SIZE = 256 * 1024  // 256KB
    }

    private fun getVideoDuration(): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    fun emitChunks(): Flow<VideoChunk> = flow {
        val totalSize = videoFile.length()
        val durationMs = getVideoDuration()
        var chunkIndex = 0

        videoFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int

            // First chunk includes metadata
            bytesRead = input.read(buffer)
            if (bytesRead > 0) {
                val isLast = input.available() == 0
                val chunkBuilder = VideoChunk.newBuilder()
                    .setMetadata(VideoMetadata.newBuilder()
                        .setTotalSize(totalSize)
                        .setFilename(videoFile.name)
                        .setMimeType("video/mp4")
                        .setDurationMs(durationMs)
                        .build())
                    .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                    .setChunkIndex(chunkIndex++)
                    .setIsLast(isLast)

                emit(chunkBuilder.build())

                if (isLast) return@flow
            }

            // Subsequent chunks
            while (input.read(buffer).also { bytesRead = it } > 0) {
                val isLast = input.available() == 0
                emit(VideoChunk.newBuilder()
                    .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                    .setChunkIndex(chunkIndex++)
                    .setIsLast(isLast)
                    .build())

                if (isLast) break
            }
        }
    }
}
