package com.k1llerwhale.sonicsight.presentation.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.k1llerwhale.sonicsight.data.model.ModelProfile
import com.k1llerwhale.sonicsight.data.repository.GrpcVideoRepository
import com.k1llerwhale.sonicsight.grpc.StreamChunk
import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.testing.InProcessSonicSight
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.testing.GrpcCleanupRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * MU-302/303/304/306/407 and MU-701..708 — MainViewModel through seam S5
 * (mobile/mobile_test_targets.yaml). Direct handleStreamResult tests get
 * their determinism from the injected UnconfinedTestDispatcher (no virtual
 * time needed, so runBlocking — runTest's uncaught-exception ledger would
 * attribute unrelated post-pass teardown noise from streaming tests to
 * whichever runTest runs next); streaming tests run against the in-process
 * gRPC server with event-driven awaits (withTimeout bounds failure, never
 * ordering).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    @get:Rule
    val grpcCleanup = GrpcCleanupRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    // viewModelScope hard-wires Dispatchers.Main: streaming tests need a
    // REAL dispatcher there (the gRPC sender coroutine must run without a
    // test scheduler to pump), while handleStreamResult determinism comes
    // from the INJECTED testDispatcher.
    private val mainThread = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val realMain = mainThread.asCoroutineDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(realMain)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Graceful drain: queued call-cancellation tasks must run BEFORE
        // GrpcCleanupRule.after() checks that server/channel terminated —
        // shutdownNow() here would strand live gRPC calls.
        mainThread.shutdown()
        mainThread.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        realMain.close()
    }

    private fun vm(repository: GrpcVideoRepository = GrpcVideoRepository({ error("no stub") }, { emptyFlow() })) =
        MainViewModel(
            Application(),
            repository = repository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            clock = { 1_000_000L },
        )

    private fun vmOn(server: InProcessSonicSight) =
        vm(GrpcVideoRepository({ server.stub }, { emptyFlow() }))

    private fun audioResult(
        seq: Int,
        modelId: String = ModelProfile.DEFAULT.id,
        samples: Int = 2,
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): StreamResult = StreamResult.newBuilder()
        .setSuccess(true)
        .setSequenceNumber(seq)
        .setAudioSampleCount(samples)
        .setModelId(modelId)
        .setLeftAudioPcm(ByteString.copyFrom(bytes))
        .setRightAudioPcm(ByteString.copyFrom(bytes))
        .build()

    private suspend fun awaitCondition(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("timed out awaiting $what")
            Thread.yield()
        }
    }

    // ── MU-302/303 · staleness filter on the echoed model id ────────────

    @Test
    fun mu302_staleModelIdResultNeverReachesPlaybackFlows() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.selectModel(ModelProfile.SONICSIGHT)
        m.leftAudioChunks.test {
            m.handleStreamResult(audioResult(seq = 1, modelId = "multisensory"))
            expectNoEvents()
        }
    }

    @Test
    fun mu303_emptyModelIdAcceptedForBackwardCompat_characterization() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.selectModel(ModelProfile.SONICSIGHT)
        m.leftAudioChunks.test {
            m.handleStreamResult(audioResult(seq = 1, modelId = ""))
            assertEquals(4, awaitItem().size)
        }
    }

    // ── MU-703 · gap silence arithmetic ─────────────────────────────────

    @Test
    fun mu703_sequenceGapInsertsExactSilence() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.leftAudioChunks.test {
            m.handleStreamResult(audioResult(seq = 1, samples = 1378))
            assertEquals(4, awaitItem().size)
            // seq jumps 1 -> 4: 2 missed results * 1378 samples * 2 bytes.
            m.handleStreamResult(audioResult(seq = 4, samples = 1378))
            assertEquals(2 * 1378 * 2, awaitItem().size)   // silence first
            assertEquals(4, awaitItem().size)              // then the audio
        }
    }

    @Test
    fun mu703_gapFallbackUses1250SamplesWhenCountAbsent() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.rightAudioChunks.test {
            m.handleStreamResult(audioResult(seq = 1, samples = 0))
            assertEquals(4, awaitItem().size)
            m.handleStreamResult(audioResult(seq = 3, samples = 0))
            assertEquals(1 * 1250 * 2, awaitItem().size)   // REC-4: ~113 ms
            assertEquals(4, awaitItem().size)
        }
    }

    // ── MU-708 · buffering and failure results ──────────────────────────

    @Test
    fun mu708_bufferingResultHasNoEffects() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.leftAudioChunks.test {
            m.handleStreamResult(
                StreamResult.newBuilder().setSuccess(true).setIsBuffering(true)
                    .setLeftAudioPcm(ByteString.copyFrom(byteArrayOf(9))).build())
            expectNoEvents()
        }
        assertEquals(UiState.Idle, m.uiState.value)
    }

    @Test
    fun mu708_failureResultSurfacesErrorMessage() = runBlocking {  // direct test: no virtual time needed; runTest would inherit unrelated uncaught-exception reports from streaming tests (see report)
        val m = vm()
        m.handleStreamResult(
            StreamResult.newBuilder().setSuccess(false).setErrorMessage("model x not loaded").build())
        assertEquals(UiState.Error("model x not loaded"), m.uiState.value)
    }

    // ── MU-701/702 · state machine and session reset ────────────────────

    @Test
    fun mu701_startStreamingEntersStreaming_resetReturnsToIdle() {
        val server = InProcessSonicSight(grpcCleanup)
        server.service.streamHandler = { flow { kotlinx.coroutines.awaitCancellation() } }
        val m = vmOn(server)
        assertEquals(UiState.Idle, m.uiState.value)
        m.startStreaming()
        assertEquals(UiState.Streaming, m.uiState.value)
        m.resetState()
        assertEquals(UiState.Idle, m.uiState.value)
    }

    @Test
    fun mu702_startStreamingResetsSessionStateAndDropsPreStartChunks() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val received = CopyOnWriteArrayList<StreamChunk>()
        val handlerEntered = CompletableDeferred<Unit>()
        server.service.streamHandler = { requests ->
            flow {
                handlerEntered.complete(Unit)
                requests.collect { received.add(it) }
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val m = vmOn(server)
        m.setFrozen(true)

        // Buffered into the pre-start field flow — must never reach the
        // fresh per-stream flow ("never replay old-profile chunks").
        m.sendStreamChunk(StreamChunk.newBuilder().setTimestampMs(111).build())

        m.startStreaming()
        assertFalse("freeze must clear on stream start", m.frozen)
        withTimeout(5000) { handlerEntered.await() }

        // Handshake: a replay-0 SharedFlow drops emissions until the gRPC
        // sender has subscribed, and the drop policy means a single send
        // can lose to buffer pressure — so RE-SEND the counted chunk until
        // it lands (duplicates are fine; the assertion is presence of 222
        // and absence of the pre-start 111).
        awaitCondition("post-start chunk arrives") {
            m.sendStreamChunk(StreamChunk.newBuilder().setTimestampMs(222).build())
            received.any { it.timestampMs == 222L }
        }

        // The pre-start chunk (ts=111) must never appear; pings (ts=0) may.
        assertEquals(emptyList<Long>(), received.map { it.timestampMs }.filter { it == 111L })
        m.stopStreaming()
    }

    // ── MU-304 · switch cycle: cancel, reopen, stale results dropped ────

    @Test
    fun mu304_singleSwitchCycle() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val resultBus = MutableSharedFlow<StreamResult>(replay = 0, extraBufferCapacity = 16)
        // Handler-entered gate per stream: the header capture fires in the
        // interceptor BEFORE the handler's flow is collected, and a replay-0
        // bus drops emissions with no subscriber.
        val streamsLive = java.util.concurrent.atomic.AtomicInteger(0)
        val streamsCancelled = java.util.concurrent.atomic.AtomicInteger(0)
        server.service.streamHandler = {
            flow {
                streamsLive.incrementAndGet()
                try {
                    resultBus.collect { emit(it) }
                } finally {
                    streamsCancelled.incrementAndGet()
                }
            }
        }
        val m = vmOn(server)

        m.selectModel(ModelProfile.MULTISENSORY)
        m.startStreaming()
        awaitCondition("first stream live") { streamsLive.get() == 1 }

        m.leftAudioChunks.test {
            resultBus.emit(audioResult(seq = 1, modelId = "multisensory"))
            assertEquals(4, awaitItem().size)

            // Switch: cancel + reopen with new metadata (the activity's
            // protocol, driven here directly).
            m.stopStreaming()
            m.selectModel(ModelProfile.SONICSIGHT)
            m.startStreaming()
            awaitCondition("second stream live") { streamsLive.get() == 2 }

            // In-flight result from the OLD model: dropped by the filter.
            // Distinct payload size (6 B) so a leak would surface as a
            // mis-sized item on the next await, not vanish into timing.
            resultBus.emit(audioResult(seq = 2, modelId = "multisensory",
                bytes = byteArrayOf(9, 9, 9, 9, 9, 9)))

            // Result for the new selection: delivered. If the stale result
            // had leaked, this awaitItem would see 6 bytes and fail.
            resultBus.emit(audioResult(seq = 1, modelId = "sonicsight"))
            assertEquals(4, awaitItem().size)
        }
        assertEquals(listOf("multisensory", "sonicsight"), server.headers.captured.toList())
        m.stopStreaming()
        // Deterministic teardown: both streams observed their cancellation
        // before GrpcCleanupRule verifies resource release.
        awaitCondition("both streams cancelled") { streamsCancelled.get() == 2 }
    }

    // ── MU-306 · FAILED_PRECONDITION surfaces as a typed error state ────

    @Test
    fun mu306_failedPreconditionBecomesErrorState() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        server.service.streamHandler = {
            flow { throw StatusException(Status.FAILED_PRECONDITION.withDescription("Unknown model 'zzz'")) }
        }
        val m = vmOn(server)
        m.startStreaming()
        awaitCondition("error state") { m.uiState.value is UiState.Error }
        val message = (m.uiState.value as UiState.Error).message
        assertTrue("message should carry the status: $message",
            message.contains("FAILED_PRECONDITION"))
    }

    // ── MU-704 · no emissions after cancellation ────────────────────────

    @Test
    fun mu704_noEmissionsAfterStop() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val resultBus = MutableSharedFlow<StreamResult>(replay = 0, extraBufferCapacity = 16)
        val handlerEntered = CompletableDeferred<Unit>()
        val handlerCancelled = CompletableDeferred<Unit>()
        server.service.streamHandler = {
            flow {
                handlerEntered.complete(Unit)
                try {
                    resultBus.collect { emit(it) }
                } finally {
                    handlerCancelled.complete(Unit)
                }
            }
        }
        val m = vmOn(server)
        m.startStreaming()
        withTimeout(5000) { handlerEntered.await() }

        m.stopStreaming()
        // Deterministic: once the server observed the cancellation, the
        // producer is gone — a post-cancel emission has no path to the
        // client. No wall-clock settle needed.
        withTimeout(5000) { handlerCancelled.await() }
        m.leftAudioChunks.test {
            resultBus.emit(audioResult(seq = 1))
            expectNoEvents()
        }
    }

    // ── MU-605 · pixel/freeze protocol, ViewModel half ──────────────────
    // (The frame/audio-chunk freeze stamping lives in MainActivity's
    // capture paths — instrumented tier; here: the query contract.)

    @Test
    fun mu605_pixelQueryCarriesWindowZeroFreezeAndClusters() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val received = CopyOnWriteArrayList<StreamChunk>()
        server.service.streamHandler = { requests ->
            flow {
                requests.collect { received.add(it) }
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val m = vmOn(server)
        m.selectModel(ModelProfile.SONICSIGHT_PIXEL)
        m.startStreaming()
        awaitCondition("request pipe live") {
            m.sendStreamChunk(StreamChunk.newBuilder().setTimestampMs(0).build())
            received.isNotEmpty()
        }

        m.setFrozen(true)
        m.sendPixelQuery(0.5f, 0.25f, 1f / 14f)
        awaitCondition("query chunk arrives") { received.any { it.queriesCount > 0 } }
        val qc = received.first { it.queriesCount > 0 }
        val q = qc.getQueries(0)
        assertEquals("window_id must always be 0 (server redirects while frozen, A2)",
            0L, q.windowId)
        assertEquals(0.5f, q.xNorm, 0f)
        assertEquals(0.25f, q.yNorm, 0f)
        assertEquals(1f / 14f, q.radiusNorm, 0f)
        assertFalse(q.sticky)
        assertTrue("freeze level flag stamped on the query chunk", qc.freeze)
        assertTrue("request_clusters stamped on the query chunk", qc.requestClusters)

        // Sticky follow (A3) and its release.
        m.startFollow(0.5f, 0.5f, 1.5f / 14f)
        awaitCondition("sticky query arrives") {
            received.any { it.queriesCount > 0 && it.getQueries(0).sticky }
        }
        m.stopFollow()
        awaitCondition("clear_sticky chunk arrives") { received.any { it.clearSticky } }
        m.stopStreaming()
    }

    // ── MU-407 · loss policy: audio lossless in-order, frames droppable ─

    @Test
    fun mu407_audioChunksArriveLosslessInOrder() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val received = CopyOnWriteArrayList<Long>()
        server.service.streamHandler = { requests ->
            flow {
                requests.collect { received.add(it.timestampMs) }
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val m = vmOn(server)
        m.startStreaming()

        // Handshake first (see mu702): suspending emit on a replay-0 flow
        // with no subscriber yet returns without delivering.
        awaitCondition("request pipe live") {
            m.sendStreamChunk(StreamChunk.newBuilder().setTimestampMs(0).build())
            received.isNotEmpty()
        }
        for (i in 1..20) {
            m.sendAudioChunk(StreamChunk.newBuilder().setTimestampMs(i.toLong()).build())
        }
        awaitCondition("all 20 audio chunks") { received.count { it > 0 } == 20 }
        assertEquals((1L..20L).toList(), received.filter { it > 0 })
        m.stopStreaming()
    }

    @Test
    fun mu407_framePathIsNonSuspendingDropPolicy() {
        // Policy shape pinned at the API boundary: sendStreamChunk is a
        // plain function backed by tryEmit — thousands of calls with no
        // consumer return synchronously and drop silently, exactly the
        // "frames may be dropped" half of the contract. The other half —
        // that sendAudioChunk's suspending emit actually PARKS under
        // saturation — is not provable off-device: the in-process gRPC
        // transport enforces no flow control, so the out-queue never
        // saturates here (recorded as not measured at unit level; the
        // in-order lossless delivery half is mu407_audioChunksArrive...).
        val m = vm()
        repeat(5000) { m.sendStreamChunk(StreamChunk.newBuilder().setTimestampMs(it.toLong()).build()) }
        // Reaching this line synchronously IS the assertion.
        assertTrue(true)
    }
}
