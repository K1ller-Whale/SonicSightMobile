package com.k1llerwhale.sonicsight.data.api

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import com.k1llerwhale.sonicsight.grpc.SonicSightServiceGrpcKt
import java.util.concurrent.TimeUnit

object GrpcModule {
    // Default server address; override at runtime via setHost() (persisted in
    // SharedPreferences). Raw IP or hostname only, no scheme.
    private const val DEFAULT_HOST = "192.168.1.87"
    private const val PREFS = "sonicsight_prefs"
    private const val KEY_HOST = "server_host"

    // Locked transport constants (MU-401) — the single source the channel
    // builder reads. Internal so tests pin the exact values used.
    internal const val PORT = 50051 // gRPC port, not the FastAPI port
    internal const val MAX_INBOUND_BYTES = 16 * 1024 * 1024 // 16MB
    internal const val KEEPALIVE_TIME_SECONDS = 30L
    internal const val KEEPALIVE_TIMEOUT_SECONDS = 10L

    /** Seam S3 (docs/SEAMS.md): swappable host persistence for JVM tests. */
    interface HostStore {
        fun load(): String?
        fun save(host: String)
    }

    @VisibleForTesting
    internal var hostStoreOverride: HostStore? = null

    /** Seam S3: swappable channel construction for JVM tests. */
    @VisibleForTesting
    internal var channelFactoryOverride: ((host: String, port: Int) -> ManagedChannel)? = null

    /** Test hygiene only: restore pristine singleton state between tests. */
    @VisibleForTesting
    internal fun resetForTest() {
        shutdown()
        host = DEFAULT_HOST
        hostStoreOverride = null
        channelFactoryOverride = null
    }

    @Volatile
    private var host: String = DEFAULT_HOST

    @Volatile
    private var channel: ManagedChannel? = null

    /** Load the persisted host override. Call once, before first stub use. */
    fun init(context: Context) {
        val store = hostStoreOverride
        host = if (store != null) {
            store.load() ?: DEFAULT_HOST
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        }
    }

    fun currentHost(): String = host

    /**
     * Persist a new server host. The current channel is shut down; the next
     * stub access reconnects to the new address. Do not call mid-stream.
     */
    fun setHost(context: Context, newHost: String) {
        val trimmed = newHost.trim()
        if (trimmed.isEmpty()) return
        val store = hostStoreOverride
        if (store != null) {
            store.save(trimmed)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HOST, trimmed).apply()
        }
        if (trimmed != host) {
            host = trimmed
            shutdown()
        }
    }

    @Synchronized
    @VisibleForTesting
    internal fun channelInstance(): ManagedChannel {
        val existing = channel
        if (existing != null && !existing.isShutdown) return existing
        val factory = channelFactoryOverride
        val created = factory?.invoke(host, PORT)
            ?: ManagedChannelBuilder.forAddress(host, PORT)
                .usePlaintext() // Use insecure connection for development
                .maxInboundMessageSize(MAX_INBOUND_BYTES)
                .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)  // Detect dead connections even when idle
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)  // Fail fast on unresponsive server
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
