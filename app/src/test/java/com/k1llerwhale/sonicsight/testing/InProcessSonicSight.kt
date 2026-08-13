package com.k1llerwhale.sonicsight.testing

import com.k1llerwhale.sonicsight.grpc.InferenceResult
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.grpc.VideoChunk
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * In-process gRPC backbone for the MU-3xx/MU-4xx suites: the real generated
 * service stub over the real gRPC call path, no sockets, behaviour scripted
 * per test via [streamHandler] / [processVideoHandler].
 *
 * Known in-process transport limitation (documented, relevant to MU-402):
 * messages are passed by reference without serialization, so inbound
 * message-size limits are NOT enforced here — the 16 MB cap's effect cannot
 * be exercised in-process, only its configuration value.
 */
class FakeSonicSightService : SonicSightServiceGrpcKt.SonicSightServiceCoroutineImplBase() {

    @Volatile
    var streamHandler: (Flow<StreamChunk>) -> Flow<StreamResult> = { emptyFlow() }

    @Volatile
    var processVideoHandler: suspend (Flow<VideoChunk>) -> InferenceResult =
        { suspendCancellableCoroutine {} }   // never completes unless scripted

    override fun streamProcess(requests: Flow<StreamChunk>): Flow<StreamResult> =
        streamHandler(requests)

    override suspend fun processVideo(requests: Flow<VideoChunk>): InferenceResult =
        processVideoHandler(requests)
}

/** Captures the `sonicsight-model` header of every call, in arrival order. */
class ModelHeaderCaptureInterceptor : ServerInterceptor {
    private val key: Metadata.Key<String> =
        Metadata.Key.of("sonicsight-model", Metadata.ASCII_STRING_MARSHALLER)

    val captured = CopyOnWriteArrayList<String?>()

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        captured.add(headers.get(key))
        return next.startCall(call, headers)
    }
}

/** One in-process server + channel + coroutine stub, cleaned up by [rule]. */
class InProcessSonicSight(rule: GrpcCleanupRule) {
    val service = FakeSonicSightService()
    val headers = ModelHeaderCaptureInterceptor()

    private val name = InProcessServerBuilder.generateName()

    init {
        rule.register(
            InProcessServerBuilder.forName(name)
                .addService(service)
                .intercept(headers)
                .build()
                .start()
        )
    }

    private val channel = rule.register(InProcessChannelBuilder.forName(name).build())

    val stub: SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub =
        SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub(channel)
}
