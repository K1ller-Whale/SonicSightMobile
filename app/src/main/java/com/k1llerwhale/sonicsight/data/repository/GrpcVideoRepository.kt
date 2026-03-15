package com.k1llerwhale.sonicsight.data.repository

import com.k1llerwhale.sonicsight.data.api.GrpcModule
import com.k1llerwhale.sonicsight.grpc.InferenceResult
import com.k1llerwhale.sonicsight.util.VideoChunkEmitter
import java.io.File

class GrpcVideoRepository {

    /**
     * Streams video chunks to the backend and returns the inference result.
     * Uses client-side streaming (Flow<VideoChunk> -> InferenceResult).
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
}
