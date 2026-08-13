package com.k1llerwhale.sonicsight.data.repository

import com.k1llerwhale.sonicsight.grpc.StreamResult
import com.k1llerwhale.sonicsight.testing.InProcessSonicSight
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.testing.GrpcCleanupRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * MU-301/307 and MU-403..406 — transport behaviour against the real
 * generated stubs over an in-process gRPC server
 * (mobile/mobile_test_targets.yaml; harness: testing/InProcessSonicSight).
 * runBlocking, not runTest: the assertions are event-driven against real
 * gRPC executors; withTimeout bounds failure, never ordering.
 */
class GrpcTransportTest {

    @get:Rule
    val grpcCleanup = GrpcCleanupRule()

    private fun repoOn(server: InProcessSonicSight) = GrpcVideoRepository(
        stubProvider = { server.stub },
        chunkFlowFactory = { emptyFlow() },
    )

    // ── MU-301 · metadata carries the selected model id on every open ───

    @Test
    fun mu301_metadataHeaderCarriesSelectedModelIdOnEveryOpen() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val repo = repoOn(server)

        withTimeout(5000) {
            repo.streamProcess(emptyFlow(), "multisensory").collect {}
            repo.streamProcess(emptyFlow(), "sonicsight").collect {}
        }
        assertEquals(listOf("multisensory", "sonicsight"), server.headers.captured.toList())
    }

    // ── MU-307 · header attached even for an empty id (client half of
    //    NFR-COMPAT-002; server-default-on-absent is server scope) ───────

    @Test
    fun mu307_headerAttachedIncludingEmptyId() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val repo = repoOn(server)

        withTimeout(5000) { repo.streamProcess(emptyFlow(), "").collect {} }
        assertEquals(1, server.headers.captured.size)
        assertEquals("", server.headers.captured[0])
    }

    // ── MU-403 · status codes propagate raw — no mapping layer exists
    //    (characterization; candidate defect: unused catch/map imports) ──

    @Test
    fun mu403_statusCodesPropagateRawWithoutMapping() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val repo = repoOn(server)
        val codes = listOf(
            Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED,
            Status.FAILED_PRECONDITION, Status.INTERNAL,
        )
        for (status in codes) {
            server.service.streamHandler = { flow { throw StatusException(status) } }
            try {
                withTimeout(5000) { repo.streamProcess(emptyFlow(), "sonicsight").collect {} }
                fail("expected StatusException ${status.code}")
            } catch (e: StatusException) {
                // Current behaviour: the raw StatusException reaches the
                // collector untranslated. Typed mapping is absent (finding).
                assertEquals(status.code, e.status.code)
            }
        }
    }

    // ── MU-404 · processVideo swallows CancellationException
    //    (characterization; candidate defect: breaks structured
    //    concurrency — GrpcVideoRepository.kt catch(Exception)) ──────────

    @Test
    fun mu404_processVideoSwallowsCancellationIntoResultFailure() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val entered = CompletableDeferred<Unit>()
        server.service.processVideoHandler = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val repo = repoOn(server)
        val outcome = AtomicReference<kotlin.Result<*>?>(null)

        val job = launch {
            outcome.set(repo.processVideo(File.createTempFile("mu404", ".mp4")))
        }
        withTimeout(5000) { entered.await() }
        job.cancel()
        job.join()

        // Current behaviour: cancellation is converted into Result.failure
        // instead of propagating — the caller sees an "upload failure".
        val result = outcome.get()
        assertNotNull("processVideo returned nothing on cancellation", result)
        assertTrue(result!!.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    // ── MU-405 · cancelling the collector closes the stream server-side ─

    @Test
    fun mu405_collectorCancellationClosesStreamServerSide() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        val entered = CompletableDeferred<Unit>()
        val serverSawCancel = CompletableDeferred<Unit>()
        server.service.streamHandler = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    serverSawCancel.complete(Unit)
                }
            }
        }
        val repo = repoOn(server)

        val job = launch { repo.streamProcess(emptyFlow(), "sonicsight").collect {} }
        withTimeout(5000) { entered.await() }
        job.cancel()
        job.join()
        withTimeout(5000) { serverSawCancel.await() }   // fails the test if never seen
    }

    // ── MU-406 · abrupt server termination mid-stream surfaces cleanly ──

    @Test
    fun mu406_abruptServerFailureMidStreamSurfacesAfterDeliveredResults() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        server.service.streamHandler = {
            flow {
                emit(StreamResult.newBuilder().setSuccess(true).setSequenceNumber(1).build())
                emit(StreamResult.newBuilder().setSuccess(true).setSequenceNumber(2).build())
                throw StatusException(Status.UNAVAILABLE)
            }
        }
        val repo = repoOn(server)

        val received = mutableListOf<StreamResult>()
        try {
            withTimeout(5000) {
                repo.streamProcess(emptyFlow(), "sonicsight").collect { received.add(it) }
            }
            fail("expected StatusException")
        } catch (e: StatusException) {
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
        }
        assertEquals(listOf(1, 2), received.map { it.sequenceNumber })
    }

    @Test
    fun mu406_cleanServerCompletionEndsCollectionWithoutError() = runBlocking {
        val server = InProcessSonicSight(grpcCleanup)
        server.service.streamHandler = {
            flow { emit(StreamResult.newBuilder().setSuccess(true).setSequenceNumber(7).build()) }
        }
        val repo = repoOn(server)

        val received = withTimeout(5000) {
            val list = mutableListOf<Int>()
            repo.streamProcess(emptyFlow(), "sonicsight").collect { list.add(it.sequenceNumber) }
            list
        }
        assertEquals(listOf(7), received)
    }
}
