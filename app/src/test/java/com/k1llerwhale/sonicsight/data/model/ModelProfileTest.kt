package com.k1llerwhale.sonicsight.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MU-305 — capture-profile constants and invariants
 * (mobile/mobile_test_targets.yaml MU-305). These are the locked constants
 * the whole pipeline hangs off; a silent change here breaks output without
 * any error, which is exactly why they are pinned.
 */
class ModelProfileTest {

    @Test
    fun mu305_captureRateIsTheOnlyGuaranteedAndroidRate() {
        assertEquals(44100, ModelProfile.CAPTURE_RATE)
    }

    @Test
    fun mu305_sonicsightProfileTable() {
        val p = ModelProfile.SONICSIGHT
        assertEquals("sonicsight", p.id)
        assertEquals(125L, p.frameIntervalMs)      // 8 fps
        assertEquals(4, p.decimFactor)             // 44100/4 = 11025
        assertEquals(5000.0, p.decimCutoffHz, 0.0)
        assertEquals(11025, p.streamRate)
        assertEquals(FrameKind.LEFT_RIGHT_HALVES, p.frameKind)
        assertEquals(2, p.heatmapCount)
        assertEquals(false, p.confidenceGated)
        assertEquals(false, p.isPixel)
    }

    @Test
    fun mu305_multisensoryProfileTable() {
        val p = ModelProfile.MULTISENSORY
        assertEquals("multisensory", p.id)
        assertEquals(33L, p.frameIntervalMs)       // ~30 fps
        assertEquals(2, p.decimFactor)             // 44100/2 = 22050
        assertEquals(10000.0, p.decimCutoffHz, 0.0)
        assertEquals(22050, p.streamRate)          // wire rate — see MU-802
        assertEquals(FrameKind.FULL_LETTERBOXED, p.frameKind)
        assertEquals(1, p.heatmapCount)
        assertEquals(true, p.confidenceGated)
        assertEquals(false, p.isPixel)
    }

    @Test
    fun mu305_pixelProfileTable() {
        val p = ModelProfile.SONICSIGHT_PIXEL
        assertEquals("sonicsight-pixel", p.id)
        assertEquals(125L, p.frameIntervalMs)      // same cadence as halves
        assertEquals(4, p.decimFactor)             // same audio as halves —
        assertEquals(11025, p.streamRate)          // full frames do NOT imply hi rate
        assertEquals(5000.0, p.decimCutoffHz, 0.0)
        assertEquals(FrameKind.FULL_LETTERBOXED, p.frameKind)
        assertEquals(0, p.heatmapCount)            // energy map replaces heatmaps
        assertEquals(true, p.isPixel)
    }

    @Test
    fun mu305_everyProfileDecimatesExactlyFromCaptureRate() {
        for (p in ModelProfile.ALL) {
            assertEquals("${p.id}: streamRate * decimFactor must equal 44100",
                ModelProfile.CAPTURE_RATE, p.streamRate * p.decimFactor)
        }
    }

    @Test
    fun mu305_everyProfileCutoffBelowOutputNyquist() {
        for (p in ModelProfile.ALL) {
            val nyquist = ModelProfile.CAPTURE_RATE / (2.0 * p.decimFactor)
            assertTrue("${p.id}: cutoff ${p.decimCutoffHz} must be < $nyquist",
                p.decimCutoffHz < nyquist)
        }
    }

    @Test
    fun mu305_byIdRoundTripsForAllProfiles() {
        for (p in ModelProfile.ALL) {
            assertSame(p, ModelProfile.byId(p.id))
        }
    }

    @Test
    fun mu305_byIdUnknownFallsBackToDefault_characterization() {
        // Characterization, candidate finding: a typo'd or stale persisted id
        // silently selects the DEFAULT capture profile client-side while the
        // server rejects the stream with FAILED_PRECONDITION — the two ends
        // disagree about what a bad id means (ModelProfile.kt:101).
        assertSame(ModelProfile.DEFAULT, ModelProfile.byId("no-such-model"))
        assertSame(ModelProfile.DEFAULT, ModelProfile.byId(""))
    }

    @Test
    fun mu305_defaultIsSonicsight() {
        assertSame(ModelProfile.SONICSIGHT, ModelProfile.DEFAULT)
    }

    @Test
    fun mu305_nextCyclesThroughAllProfilesAndWraps() {
        assertSame(ModelProfile.MULTISENSORY, ModelProfile.next(ModelProfile.SONICSIGHT))
        assertSame(ModelProfile.SONICSIGHT_PIXEL, ModelProfile.next(ModelProfile.MULTISENSORY))
        assertSame(ModelProfile.SONICSIGHT, ModelProfile.next(ModelProfile.SONICSIGHT_PIXEL))
    }
}
