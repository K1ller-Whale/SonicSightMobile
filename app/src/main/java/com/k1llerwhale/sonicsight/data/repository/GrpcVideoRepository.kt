package com.k1llerwhale.sonicsight.data.repository

import androidx.annotation.VisibleForTesting
import com.k1llerwhale.sonicsight.data.api.GrpcModule
import com.k1llerwhale.sonicsight.grpc.InferenceResult
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.grpc.VideoChunk
import com.k1llerwhale.sonicsight.util.VideoChunkEmitter
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Test seam S2 (docs/SEAMS.md): both dependencies are constructor-injected
 * with production defaults, so `GrpcVideoRepository()` behaves exactly as
 * before while tests substitute an in-process stub and synthetic chunk flows.
 */
class GrpcVideoRepository(
    private val stubProvider: () -> SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub = { GrpcModule.stub },
    private val chunkFlowFactory: (File) -> Flow<VideoChunk> = { VideoChunkEmitter(it).emitChunks() },
) {

    /**
     * Streams video chunks to the backend and returns the inference result.
     * Uses client-side streaming (Flow<VideoChunk> -> InferenceResult).
     * (Kept for backward compatibility or one-off file uploads)
     */
    suspend fun processVideo(videoFile: File): kotlin.Result<InferenceResult> {
        return try {
            val chunkFlow = chunkFlowFactory(videoFile)

            // processVideo is a client-streaming RPC
            val response = stubProvider().processVideo(chunkFlow)

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
     * @param modelId Sent as "sonicsight-model" gRPC metadata; the server
     *   selects the model per stream and echoes it in every StreamResult.
     *   Switching models = cancel this stream and call again — never
     *   mid-stream, the capture profiles are incompatible.
     * @return A Flow of StreamResult objects returning heatmaps/audio from the server.
     */
    fun streamProcess(chunks: Flow<StreamChunk>, modelId: String): Flow<StreamResult> {
        val headers = Metadata()
        headers.put(MODEL_METADATA_KEY, modelId)
        return stubProvider()
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .streamProcess(chunks)
    }

    companion object {
        @VisibleForTesting
        internal val MODEL_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("sonicsight-model", Metadata.ASCII_STRING_MARSHALLER)
    }
}
