package com.k1llerwhale.sonicsight.data.repository

import com.k1llerwhale.sonicsight.data.api.GrpcModule
import com.k1llerwhale.sonicsight.grpc.InferenceResult
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.util.VideoChunkEmitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File

class GrpcVideoRepository {

    /**
     * Streams video chunks to the backend and returns the inference result.
     * Uses client-side streaming (Flow<VideoChunk> -> InferenceResult).
     * (Kept for backward compatibility or one-off file uploads)
     */
    suspend fun processVideo(videoFile: File): kotlin.Result<InferenceResult> {
        return try {
            val chunkEmitter = VideoChunkEmitter(videoFile)
            val chunkFlow = chunkEmitter.emitChunks()

            // processVideo is a client-streaming RPC
            val response = GrpcModule.stub.processVideo(chunkFlow)

            if (response.success) {
                kotlin.Result.success(response)
            } else {
                kotlin.Result.failure(Exception(response.errorMessage))
            }
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    /**
     * Initiates a bidirectional stream for near real-time processing.
     * @param chunks A Flow of StreamChunk objects (raw frames/audio) emitted from the camera/mic.
     * @return A Flow of StreamResult objects returning heatmaps/audio from the server.
     */
    fun streamProcess(chunks: Flow<StreamChunk>): Flow<StreamResult> {
        return GrpcModule.stub.streamProcess(chunks)
    }
}
