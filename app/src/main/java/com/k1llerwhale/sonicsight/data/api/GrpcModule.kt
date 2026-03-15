package com.k1llerwhale.sonicsight.data.api

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import java.util.concurrent.TimeUnit

object GrpcModule {
    // Update this to your server's IP address
    private const val HOST = "10.0.2.2" // Localhost for Android Emulator
    private const val PORT = 50051

    private val channel: ManagedChannel by lazy {
        ManagedChannelBuilder.forAddress(HOST, PORT)
            .usePlaintext() // Use insecure connection for development
            .maxInboundMessageSize(16 * 1024 * 1024) // 16MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .defaultCompression(grpc.Gzip.name()) // Enable Gzip
            .build()
    }

    val stub: SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub by lazy {
        SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub(channel)
    }

    fun shutdown() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
