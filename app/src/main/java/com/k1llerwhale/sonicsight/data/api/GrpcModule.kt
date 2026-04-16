package com.k1llerwhale.sonicsight.data.api

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import java.util.concurrent.TimeUnit

object GrpcModule {
    // Update this to your server's IP address
    // Use raw IP or hostname only. NO "http://" or "https://"
    private const val HOST = "192.168.26.82"
    private const val PORT = 50051 // Use the gRPC port, not FastAPI port

    private val channel: ManagedChannel by lazy {
        ManagedChannelBuilder.forAddress(HOST, PORT)
            .usePlaintext() // Use insecure connection for development
            .maxInboundMessageSize(16 * 1024 * 1024) // 16MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .build()
    }

    val stub: SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub by lazy {
        SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub(channel)
    }

    fun shutdown() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
