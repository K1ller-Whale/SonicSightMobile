package com.k1llerwhale.sonicsight.data.api

import android.content.Context
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import java.util.concurrent.TimeUnit

object GrpcModule {
    // Default server address; override at runtime via setHost() (persisted in
    // SharedPreferences). Raw IP or hostname only, no scheme.
    private const val DEFAULT_HOST = "192.168.1.87"
    private const val PORT = 50051 // gRPC port, not the FastAPI port
    private const val PREFS = "sonicsight_prefs"
    private const val KEY_HOST = "server_host"

    @Volatile
    private var host: String = DEFAULT_HOST

    @Volatile
    private var channel: ManagedChannel? = null

    /** Load the persisted host override. Call once, before first stub use. */
    fun init(context: Context) {
        host = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    }

    fun currentHost(): String = host

    /**
     * Persist a new server host. The current channel is shut down; the next
     * stub access reconnects to the new address. Do not call mid-stream.
     */
    fun setHost(context: Context, newHost: String) {
        val trimmed = newHost.trim()
        if (trimmed.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST, trimmed).apply()
        if (trimmed != host) {
            host = trimmed
            shutdown()
        }
    }

    @Synchronized
    private fun channelInstance(): ManagedChannel {
        val existing = channel
        if (existing != null && !existing.isShutdown) return existing
        val created = ManagedChannelBuilder.forAddress(host, PORT)
            .usePlaintext() // Use insecure connection for development
            .maxInboundMessageSize(16 * 1024 * 1024) // 16MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)  // Detect dead connections even when idle
            .keepAliveTimeout(10, TimeUnit.SECONDS)  // Fail fast on unresponsive server
            .build()
        channel = created
        return created
    }

    val stub: SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub
        get() = SonicSightServiceGrpcKt.SonicSightServiceCoroutineStub(channelInstance())

    @Synchronized
    fun shutdown() {
        channel?.shutdown()
        channel = null
    }
}
