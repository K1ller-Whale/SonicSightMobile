package com.k1llerwhale.sonicsight.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * MU-2xx — jitter buffer suite (mobile/mobile_test_targets.yaml). Runs on
 * the JVM through test seam S1 (PcmSink, docs/SEAMS.md): the drain loop is
 * paced by the sink's blocking write, so a semaphore-stepped fake sink makes
 * every drain cycle deterministic — the test releases exactly N permits and
 * observes exactly N writes. Wall-clock is used only as a failure deadline
 * in [await], never for ordering.
 */
class JitterBufferTest {

    private class FakeSink : PcmSink {
        val writes = CopyOnWriteArrayList<ByteArray>()
        val writePermits = Semaphore(0)
        val writeCount = AtomicInteger()
        val playCount = AtomicInteger()
        val writeEntries = AtomicInteger()   // incremented BEFORE parking

        override val isReady = true
        override val isPlaying get() = playCount.get() > 0
        override fun play() { playCount.incrementAndGet() }

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            writeEntries.incrementAndGet()
            writePermits.acquire()   // parked here until the test steps the drain
            writes.add(data.copyOfRange(offset, offset + size))
            writeCount.incrementAndGet()
            return size
        }
    }

    private var sink = FakeSink()
    private val created = mutableListOf<Pair<JitterBuffer, FakeSink>>()

    private fun buffer(
        sampleRate: Int = 11025,
        initialBufferMs: Int = 200,
        maxBufferMs: Int = 1500,
    ): JitterBuffer {
        sink = FakeSink()
        return JitterBuffer(sink, sampleRate, initialBufferMs, maxBufferMs).also {
            created.add(it to sink)
            it.start()
        }
    }

    @After
    fun tearDown() {
        // Stop every buffer and free any drain thread parked in its fake
        // sink so it can observe running == false and exit; threads are
        // daemons either way.
        for ((b, s) in created) {
            b.stop()
            s.writePermits.release(1_000_000)
        }
        created.clear()
    }

    private fun await(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("timed out awaiting $what")
            Thread.yield()
        }
    }

    private fun pattern(n: Int, phase: Int = 0): ByteArray =
        ByteArray(n) { (((it + phase) % 255) + 1).toByte() }   // never zero

    // ── MU-201 · prebuffer gate (4410 B @ 11025, 8820 B @ 22050) ────────

    @Test
    fun mu201_playbackHeldBelowPrebufferThreshold_11025() {
        val b = buffer(11025)
        b.write(pattern(4408))
        // 4408 < 4410: play() is unreachable regardless of thread timing.
        assertEquals(0, sink.playCount.get())
        b.write(pattern(2, phase = 4408))
        await("play after threshold") { sink.playCount.get() == 1 }
    }

    @Test
    fun mu201_playbackHeldBelowPrebufferThreshold_22050() {
        val b = buffer(22050)
        b.write(pattern(8818))
        assertEquals(0, sink.playCount.get())
        b.write(pattern(2, phase = 8818))
        await("play after threshold") { sink.playCount.get() == 1 }
    }

    // ── MU-202 · capacity 33075/66150 B, drop-oldest overflow ───────────

    @Test
    fun mu202_capacityIsRateScaled() {
        // initialBufferMs 2000 > maxBufferMs: the prebuffer threshold is
        // unreachable, so the drain thread never consumes from the ring and
        // the level read is deterministic.
        val b = buffer(11025, initialBufferMs = 2000)
        b.write(pattern(40_000))
        assertEquals(1500, b.bufferLevelMs())        // clamped at 33075 B

        val b2 = buffer(22050, initialBufferMs = 2000)
        b2.write(pattern(70_000))
        assertEquals(1500, b2.bufferLevelMs())       // clamped at 66150 B
    }

    @Test
    fun mu202_singleOversizedChunkKeepsNewestTail() {
        val b = buffer(11025)
        val big = pattern(40_000)
        b.write(big)
        await("play") { sink.playCount.get() == 1 }
        sink.writePermits.release(1)
        await("first drain") { sink.writeCount.get() == 1 }
        // Oldest 40000-33075 = 6925 bytes dropped; drain starts at the tail.
        assertArrayEquals(big.copyOfRange(6925, 6925 + 512), sink.writes[0])
    }

    @Test
    fun mu202_incrementalOverflowDropsOldestFirst() {
        // Threshold 1400 ms = 30870 B sits between the first write (30000 B)
        // and the post-overflow fill (33075 B): the drain cannot start until
        // the LAST write completes, so ring content is deterministic.
        val b = buffer(11025, initialBufferMs = 1400)
        val a = pattern(30_000)
        val c = pattern(5_000, phase = 77)
        b.write(a)
        assertEquals(0, sink.playCount.get())         // 30000 < 30870
        b.write(c)                                    // 35000 > 33075: drop 1925 of a
        // (No level assert here: once the threshold is crossed the drain's
        // pre-read may consume 512 B — the clamp is pinned in mu202_capacity.)
        await("play") { sink.playCount.get() == 1 }
        sink.writePermits.release(1)
        await("first drain") { sink.writeCount.get() == 1 }
        assertArrayEquals(a.copyOfRange(1925, 1925 + 512), sink.writes[0])
    }

    // ── MU-203 · steady-state drain chunk is 512 B of real audio ────────

    @Test
    fun mu203_steadyStateWritesAre512Bytes() {
        val b = buffer(11025)
        val data = pattern(4410 + 4 * 512)
        b.write(data)
        await("play") { sink.playCount.get() == 1 }
        sink.writePermits.release(4)
        await("4 drains") { sink.writeCount.get() == 4 }
        for (i in 0 until 4) {
            assertEquals(512, sink.writes[i].size)
            assertArrayEquals(data.copyOfRange(i * 512, (i + 1) * 512), sink.writes[i])
        }
    }

    // ── MU-204/MU-206 · partial drain zero-pads; underrun inserts silence ─

    @Test
    fun mu204_mu206_partialDrainThenUnderrunSilence() {
        val b = buffer(11025)
        val data = pattern(4410)
        b.write(data)                                 // exactly the prebuffer
        await("play") { sink.playCount.get() == 1 }

        sink.writePermits.release(8)                  // 8 full 512-B chunks = 4096 B
        await("8 drains") { sink.writeCount.get() == 8 }

        // MU-206: 314 B remain; the 9th write is still 512 B — 314 real bytes
        // then 198 zeros injected mid-stream (characterization, JitterBuffer
        // partial-read path).
        sink.writePermits.release(1)
        await("partial drain") { sink.writeCount.get() == 9 }
        val ninth = sink.writes[8]
        assertEquals(512, ninth.size)
        assertArrayEquals(data.copyOfRange(4096, 4410), ninth.copyOfRange(0, 314))
        for (i in 314 until 512) assertEquals("zero pad at $i", 0, ninth[i].toInt())
        // (No underrun assertion here: the counter increments at the START of
        // the next empty cycle, before its silence write parks — asserting 0
        // between permits would race the drain thread.)

        // MU-204: ring now empty — next cycle is an underrun: 512 B of
        // silence, counter increments, no exception.
        sink.writePermits.release(1)
        await("underrun drain") { sink.writeCount.get() == 10 }
        assertTrue(sink.writes[9].all { it.toInt() == 0 })
        // The counter may read 1 or 2: the next empty cycle increments it
        // before parking on its own write. >= 1 is the deterministic claim.
        await("underrun counted") { b.underruns >= 1 }
    }

    // ── MU-205 · adaptive prebuffer: +50 ms per 3 consecutive underruns,
    //    500 ms ceiling, effective on the NEXT start only ────────────────

    @Test
    fun mu205_adaptivePrebufferStepsAndCeiling() {
        val b = buffer(11025)
        b.write(pattern(4410))
        await("play") { sink.playCount.get() == 1 }
        // Drain everything: 8 full + 1 partial = 9 writes, ring empty.
        sink.writePermits.release(9)
        await("drained") { sink.writeCount.get() == 9 }

        sink.writePermits.release(3)                  // underruns 1,2,3 → adapt
        await("adapt to 250") { b.effectiveInitialBufferMs == 250 }
        sink.writePermits.release(3)                  // 4,5,6 → adapt
        await("adapt to 300") { b.effectiveInitialBufferMs == 300 }
        sink.writePermits.release(15)                 // 9,12,15,18 → 350..500
        await("ceiling 500") { b.effectiveInitialBufferMs == 500 }
        sink.writePermits.release(6)                  // more underruns at the ceiling
        await("still draining") { sink.writeCount.get() == 9 + 3 + 3 + 15 + 6 }
        assertEquals("ceiling must hold", 500, b.effectiveInitialBufferMs)
    }

    @Test
    fun mu205_adaptedPrebufferAppliesOnNextStartOnly_characterization() {
        // Restarting cleanly requires the drain thread NOT to be parked in
        // a write when stop() runs (a parked thread survives the 500 ms join
        // and can revive into the next start — pinned separately in
        // mu210_stopWithStalledSinkLeavesZombieDrainThread). So this test
        // free-runs the drain with generous permits and targets the STABLE
        // adapted value: the 500 ms ceiling.
        val b = buffer(11025)
        b.write(pattern(4410))
        await("play") { sink.playCount.get() == 1 }
        sink.writePermits.release(100_000)            // free-run: drain + underruns
        await("adapt to ceiling") { b.effectiveInitialBufferMs == 500 }

        // stop() now joins cleanly: the thread is spinning, not parked.
        b.stop()
        val playsBefore = sink.playCount.get()
        b.start()
        // New threshold: (500*11025*2)/1000 = 11025 bytes — the adapted
        // value applies to THIS start, proving next-start-only semantics
        // (the previous stream started at the 200 ms default).
        b.write(pattern(11_023))
        assertEquals("play below adapted threshold", playsBefore, sink.playCount.get())
        b.write(pattern(2, phase = 1))
        await("play at adapted threshold") { sink.playCount.get() == playsBefore + 1 }
    }

    // ── MU-207 · FIFO byte stream: no reorder/duplicate awareness ───────

    @Test
    fun mu207_arrivalOrderPreservedDuplicatesKept() {
        val b = buffer(11025, initialBufferMs = 1)     // threshold 22 B
        val a = pattern(512, phase = 0)
        val c = pattern(512, phase = 100)
        b.write(a); b.write(c); b.write(a)            // duplicate chunk kept
        await("play") { sink.playCount.get() == 1 }
        sink.writePermits.release(3)
        await("3 drains") { sink.writeCount.get() == 3 }
        assertArrayEquals(a, sink.writes[0])
        assertArrayEquals(c, sink.writes[1])
        assertArrayEquals(a, sink.writes[2])
    }

    // ── MU-208 · burst beyond capacity clamps to newest bytes ───────────

    @Test
    fun mu208_sustainedBurstClampsLevelAtCapacity() {
        // Unreachable threshold: no drain interference; the level clamp
        // under a 100-chunk burst is deterministic.
        val b = buffer(11025, initialBufferMs = 2000)
        repeat(100) { b.write(pattern(1024, phase = it * 7)) }   // 102400 B
        assertEquals(1500, b.bufferLevelMs())
        assertEquals(0, sink.playCount.get())
    }

    @Test
    fun mu208_burstOverflowContentKeepsNewest() {
        // Threshold == capacity (33075 B): reached exactly when the FINAL
        // chunk overflows the ring, so the drain's first read is
        // deterministic. 33 x 1024 = 33792 B: the oldest 717 B drop.
        val b = buffer(11025, initialBufferMs = 1500)
        val chunks = (0 until 33).map { pattern(1024, phase = it * 7) }
        chunks.dropLast(1).forEach { b.write(it) }    // 32768 < 33075: no play
        assertEquals(0, sink.playCount.get())
        b.write(chunks.last())                        // 33792 > 33075: overflow
        await("play") { sink.playCount.get() == 1 }

        val stream = ByteArray(33 * 1024)
        var p = 0
        chunks.forEach { it.copyInto(stream, p); p += it.size }

        sink.writePermits.release(1)
        await("first drain") { sink.writeCount.get() == 1 }
        assertArrayEquals(stream.copyOfRange(717, 717 + 512), sink.writes[0])
    }

    // ── MU-209 · byte↔ms math at both rates (off-by-2x guard) ───────────

    @Test
    fun mu209_bufferLevelMsExactAtBothRates() {
        // The constructor default is 11025 (JitterBuffer.kt:28): a caller
        // that forgets to pass the speech rate gets 2x-wrong ms math. Both
        // production sites pass profile.streamRate explicitly
        // (MainActivity.kt:562-573); this pins the arithmetic they rely on.
        val b = buffer(11025)
        b.write(pattern(2205))                        // 100 ms @ 11025
        assertEquals(100, b.bufferLevelMs())

        val b2 = buffer(22050)
        b2.write(pattern(2205))                       // same bytes, 50 ms @ 22050
        assertEquals(50, b2.bufferLevelMs())
        b2.write(pattern(2205))
        assertEquals(100, b2.bufferLevelMs())
    }

    // ── MU-210 · stop/start lifecycle ───────────────────────────────────

    @Test
    fun mu210_stopResetsStateAndIgnoresWrites() {
        val b = buffer(11025)
        b.write(pattern(3000))
        assertEquals((3000 * 1000) / (11025 * 2), b.bufferLevelMs())
        b.stop()
        assertEquals(0, b.bufferLevelMs())
        b.write(pattern(1000))                        // ignored: not running
        assertEquals(0, b.bufferLevelMs())

        b.start()                                     // clean restart
        b.write(pattern(4410))
        await("play after restart") { sink.playCount.get() == 1 }
    }

    @Test
    fun mu210_stopWithStalledSinkLeavesZombieDrainThread_characterization() {
        // Candidate defect: stop() joins the drain thread for at most 500 ms
        // (JitterBuffer.kt); a thread stalled inside the sink's blocking
        // write survives stop() and completes its write afterwards. Pinned
        // as current behaviour — costs one real 500 ms join timeout.
        val b = buffer(11025)
        b.write(pattern(4410))
        await("play") { sink.playCount.get() == 1 }
        // Wait until the drain thread has actually ENTERED sink.write() —
        // with zero permits it is now parked there. Stopping earlier can hit
        // the window between play() and the first write, where the thread
        // exits cleanly and no zombie exists.
        await("drain parked in write") { sink.writeEntries.get() == 1 }
        b.stop()                                      // join(500) times out
        val writesAtStop = sink.writeCount.get()
        sink.writePermits.release(1)
        await("zombie write completes") { sink.writeCount.get() == writesAtStop + 1 }
    }
}
