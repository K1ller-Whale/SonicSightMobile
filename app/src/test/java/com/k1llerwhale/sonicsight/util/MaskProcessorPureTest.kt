package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MU-601 (JVM half) — heatmap decode math through seam S7
 * (mobile/mobile_test_targets.yaml MU-601). Render dimensions (the bitmap
 * half of MU-601/602) are covered where bitmaps exist — see the plan's
 * NOT IMPLEMENTED notes if absent.
 */
class MaskProcessorPureTest {

    // ── side inference ──────────────────────────────────────────────────

    @Test
    fun mu601_squareByteCountsInferTheirSide() {
        assertEquals(56 to 56, MaskProcessor.inferHeatmapDims(3136, 56, 56))
        assertEquals(14 to 14, MaskProcessor.inferHeatmapDims(196, 56, 56))
        assertEquals(24 to 24, MaskProcessor.inferHeatmapDims(576, 56, 56))
        assertEquals(1 to 1, MaskProcessor.inferHeatmapDims(1, 56, 56))
    }

    @Test
    fun mu601_nonSquareByteCountFallsBackToDefaults_characterization() {
        // Candidate finding: a malformed/truncated payload silently renders
        // as 56x56 (zero-padded) instead of failing.
        assertEquals(56 to 56, MaskProcessor.inferHeatmapDims(3000, 56, 56))
        assertEquals(56 to 56, MaskProcessor.inferHeatmapDims(3137, 56, 56))
    }

    @Test
    fun mu601_zeroByteCountCharacterization() {
        // 0 coerces to 1 -> side 1 -> exact square -> (1,1).
        assertEquals(1 to 1, MaskProcessor.inferHeatmapDims(0, 56, 56))
    }

    // ── gamma-2.5 alpha curve ───────────────────────────────────────────

    @Test
    fun mu601_gammaAlphaAnchorValues() {
        // Measured values — the source's old comment claimed ~18 at v=0.5
        // and ~4 at v=0.3; the arithmetic gives 35 and 9. Pinned actuals.
        assertEquals(200, MaskProcessor.gammaAlpha(1f))
        assertEquals(35, MaskProcessor.gammaAlpha(0.5f))
        assertEquals(9, MaskProcessor.gammaAlpha(0.3f))
        assertEquals(0, MaskProcessor.gammaAlpha(0f))
    }

    @Test
    fun mu601_gammaAlphaMonotoneAndClamped() {
        var prev = -1
        for (i in 0..255) {
            val a = MaskProcessor.gammaAlpha(i / 255f)
            assertTrue("alpha decreasing at $i", a >= prev)
            assertTrue("alpha above ceiling at $i", a <= 200)
            prev = a
        }
        assertEquals(200, MaskProcessor.gammaAlpha(2f))     // clamp above 1
        assertEquals(0, MaskProcessor.gammaAlpha(-1f))      // clamp below 0
    }

    // ── blend ───────────────────────────────────────────────────────────

    @Test
    fun mu601_blendEndpoints() {
        val a = 0x102030
        val b = 0xF0E0D0
        assertEquals(a, MaskProcessor.blendRgb(a, b, 0f))
        assertEquals(b, MaskProcessor.blendRgb(a, b, 1f))
    }

    // ── pixel-overlay ARGB cells ────────────────────────────────────────

    @Test
    fun mu601_pixelOverlayRejectsInvalidInput() {
        assertNull(MaskProcessor.pixelOverlayArgb(ByteArray(196), 0, 14, null, emptyMap()))
        assertNull(MaskProcessor.pixelOverlayArgb(ByteArray(196), 14, 0, null, emptyMap()))
        assertNull(MaskProcessor.pixelOverlayArgb(ByteArray(195), 14, 14, null, emptyMap()))
    }

    @Test
    fun mu601_silenceLabel255NeverTinted() {
        val energy = ByteArray(1) { 255.toByte() }
        val labels = ByteArray(1) { 255.toByte() }
        val tinted = MaskProcessor.pixelOverlayArgb(
            energy, 1, 1, labels, mapOf(255 to 0x00FF00))!!
        val untinted = MaskProcessor.pixelOverlayArgb(energy, 1, 1, null, emptyMap())!!
        assertEquals(untinted[0], tinted[0])
    }

    @Test
    fun mu601_labelledCellBlendsTowardClusterColour() {
        val energy = ByteArray(1) { 255.toByte() }
        val labels = ByteArray(1) { 3 }
        val tint = 0x00FF00
        val cell = MaskProcessor.pixelOverlayArgb(energy, 1, 1, labels, mapOf(3 to tint))!![0]
        val expectedRgb = MaskProcessor.blendRgb(
            MagmaPalette.color(1f) and 0x00FFFFFF, tint, 0.55f)
        assertEquals(expectedRgb, cell and 0x00FFFFFF)
        assertEquals(200, cell ushr 24)                    // full-energy alpha
    }

    @Test
    fun mu601_zeroEnergyCellIsTransparent() {
        val cell = MaskProcessor.pixelOverlayArgb(ByteArray(1), 1, 1, null, emptyMap())!![0]
        assertEquals(0, cell ushr 24)
    }
}
