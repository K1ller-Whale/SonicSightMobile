package com.k1llerwhale.sonicsight.data.repository

import com.k1llerwhale.sonicsight.data.api.GrpcModule
import com.k1llerwhale.sonicsight.grpc.InferenceResult
import com.k1llerwhale.sonicsight.util.VideoChunkEmitter
import kotlinx.coroutines.flow.Flow
import java.io.File

class GrpcVideoRepository {

    suspend fun processVideo(videoFile: File): Result<InferenceResult> {
        return try {
            val chunkEmitter = VideoChunkEmitter(videoFile)
            val chunkFlow = chunkEmitter.emitChunks()

            val response = GrpcModule.stub.processVideo(chunkFlow)

            if (response.success) {
                Result.success(response)
            } else {
                Result.failure(Exception(response.errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
