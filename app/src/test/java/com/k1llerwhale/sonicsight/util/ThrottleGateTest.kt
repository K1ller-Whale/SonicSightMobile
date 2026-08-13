package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MU-506/507 (gate half) — frame throttle arithmetic through seam S6 with a
 * fake clock (mobile/mobile_test_targets.yaml MU-506). The keep-latest half
 * of MU-507 is structural: the analyzer closes EVERY ImageProxy on the spot
 * (MainActivity analyzer), gate verdict decides processing only.
 */
class ThrottleGateTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun mu506_clockZeroQuirk_firstFrameBlocked_characterization() {
        // Initial-state quirk pinned: last starts at 0, so a frame at
        // clock=0 computes 0-0 < 125 and is BLOCKED. Harmless in production
        // (SystemClock.elapsedRealtime() >> interval at any real moment),
        // identical to the original closure's captured `lastAnalyzedTimestamp = 0L`.
        val clock = FakeClock(0)
        val gate = ThrottleGate(125, clock)
        assertFalse(gate.tryPass())
    }

    @Test
    fun mu506_gateAtRealisticClockValues() {
        val clock = FakeClock(1_000_000)
        val gate = ThrottleGate(125, clock)
        assertTrue("first frame passes", gate.tryPass())
        clock.now += 124
        assertFalse("124 ms later blocked", gate.tryPass())
        clock.now += 1
        assertTrue("exactly 125 ms passes", gate.tryPass())
        assertFalse("same instant blocked", gate.tryPass())
    }

    @Test
    fun mu506_thirtyFpsDeliveryThrough125msGate_isEffectively7point5Fps() {
        // 300 frames at exact 30 fps camera timestamps (10 s): the >= 125 ms
        // gate quantizes to frame boundaries — 75 passes in 10 s = 7.5 fps
        // effective, NOT 8 (characterized; REC/YAML MU-506).
        val clock = FakeClock(1_000_000)
        val gate = ThrottleGate(125, clock)
        var passes = 0
        for (i in 0 until 300) {
            clock.now = 1_000_000 + (i * 1000L) / 30
            if (gate.tryPass()) passes++
        }
        assertEquals(75, passes)
    }

    @Test
    fun mu506_thirtyFpsDeliveryThrough33msGate_passesEveryFrame() {
        // Speech profile: 33 ms interval at 30 fps delivery (frame spacing
        // 33-34 ms) passes every frame — ~30 fps effective.
        val clock = FakeClock(1_000_000)
        val gate = ThrottleGate(33, clock)
        var passes = 0
        for (i in 0 until 300) {
            clock.now = 1_000_000 + (i * 1000L) / 30
            if (gate.tryPass()) passes++
        }
        assertEquals(300, passes)
    }

    @Test
    fun mu507_blockedFramesAreNotQueued() {
        // A blocked frame leaves no residue: the next pass depends only on
        // the clock, never on how many frames were rejected in between.
        val clock = FakeClock(1_000_000)
        val gate = ThrottleGate(125, clock)
        gate.tryPass()
        repeat(50) { clock.now += 1; gate.tryPass() }   // 50 rejected frames
        clock.now += 75                                  // total 125 since pass
        assertTrue(gate.tryPass())
    }
}
