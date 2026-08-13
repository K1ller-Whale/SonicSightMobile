package com.k1llerwhale.sonicsight.data.api

import android.app.Application
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * MU-401 / MU-408 / MU-707 (persistence + validation characterization) —
 * GrpcModule through seam S3 (mobile/mobile_test_targets.yaml).
 *
 * MU-401 method note: the in-process transport skips serialization, so the
 * 16 MB cap's runtime EFFECT is not exercisable off-device; the pinned
 * configuration constants are the single source the production builder
 * reads (GrpcModule.channelInstance).
 */
class GrpcModuleTest {

    private class FakeChannel : ManagedChannel() {
        var shutdownCalled = false
        override fun shutdown(): ManagedChannel { shutdownCalled = true; return this }
        override fun shutdownNow(): ManagedChannel { shutdownCalled = true; return this }
        override fun isShutdown() = shutdownCalled
        override fun isTerminated() = shutdownCalled
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        override fun <ReqT, RespT> newCall(
            method: MethodDescriptor<ReqT, RespT>, options: CallOptions,
        ): ClientCall<ReqT, RespT> = throw UnsupportedOperationException("fake")
        override fun authority() = "fake"
    }

    private class MapHostStore(var stored: String? = null) : GrpcModule.HostStore {
        val saves = mutableListOf<String>()
        override fun load(): String? = stored
        override fun save(host: String) { saves.add(host); stored = host }
    }

    @After
    fun tearDown() = GrpcModule.resetForTest()

    // ── MU-401 · locked transport constants ─────────────────────────────

    @Test
    fun mu401_transportConstants() {
        assertEquals(50051, GrpcModule.PORT)
        assertEquals(16 * 1024 * 1024, GrpcModule.MAX_INBOUND_BYTES)
        assertEquals(16_777_216, GrpcModule.MAX_INBOUND_BYTES)
        assertEquals(30L, GrpcModule.KEEPALIVE_TIME_SECONDS)
        assertEquals(10L, GrpcModule.KEEPALIVE_TIMEOUT_SECONDS)
    }

    // ── MU-408 · host persistence round-trip and channel rebuild ────────

    @Test
    fun mu408_initLoadsPersistedHost() {
        val store = MapHostStore("10.0.0.5")
        GrpcModule.hostStoreOverride = store
        GrpcModule.init(Application())
        assertEquals("10.0.0.5", GrpcModule.currentHost())
    }

    @Test
    fun mu408_setHostPersistsTrimmedAndRebuildsChannel() {
        val store = MapHostStore("10.0.0.5")
        val builtFor = mutableListOf<Pair<String, Int>>()
        val channels = mutableListOf<FakeChannel>()
        GrpcModule.hostStoreOverride = store
        GrpcModule.channelFactoryOverride = { host, port ->
            builtFor.add(host to port)
            FakeChannel().also { channels.add(it) }
        }
        GrpcModule.init(Application())

        val first = GrpcModule.channelInstance()
        assertEquals(listOf("10.0.0.5" to 50051), builtFor)
        assertSame("channel is cached while healthy", first, GrpcModule.channelInstance())

        GrpcModule.setHost(Application(), "  server.local  ")
        assertEquals(listOf("server.local"), store.saves)      // trimmed
        assertEquals("server.local", GrpcModule.currentHost())
        assertTrue("old channel shut down on host change", channels[0].shutdownCalled)

        val second = GrpcModule.channelInstance()
        assertNotSame(first, second)
        assertEquals("server.local" to 50051, builtFor[1])
    }

    @Test
    fun mu408_setHostSameValueDoesNotShutdownChannel() {
        val store = MapHostStore("10.0.0.5")
        val channels = mutableListOf<FakeChannel>()
        GrpcModule.hostStoreOverride = store
        GrpcModule.channelFactoryOverride = { _, _ -> FakeChannel().also { channels.add(it) } }
        GrpcModule.init(Application())
        GrpcModule.channelInstance()

        GrpcModule.setHost(Application(), "10.0.0.5")
        assertFalse("unchanged host must not kill a live channel", channels[0].shutdownCalled)
    }

    // ── MU-707 · validation characterization (REC-5 addendum) ───────────

    @Test
    fun mu707_emptyAndBlankHostRejected() {
        val store = MapHostStore("initial")
        GrpcModule.hostStoreOverride = store
        GrpcModule.init(Application())
        GrpcModule.setHost(Application(), "")
        GrpcModule.setHost(Application(), "   ")
        assertEquals(emptyList<String>(), store.saves)
        assertEquals("initial", GrpcModule.currentHost())
    }

    @Test
    fun mu707_noFormatValidation_characterization() {
        // Current behaviour: anything non-blank is accepted verbatim after
        // trim — no hostname/IP/port syntax check exists. Declared product
        // gap (REC-5 addendum), not a bug; bad input surfaces later as a
        // mapped stream error.
        val store = MapHostStore(null)
        GrpcModule.hostStoreOverride = store
        GrpcModule.init(Application())
        GrpcModule.setHost(Application(), "not a host !!! 999999")
        assertEquals(listOf("not a host !!! 999999"), store.saves)
        assertEquals("not a host !!! 999999", GrpcModule.currentHost())
    }
}
