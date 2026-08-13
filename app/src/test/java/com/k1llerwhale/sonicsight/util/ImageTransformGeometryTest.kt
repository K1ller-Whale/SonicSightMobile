package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MU-501/502/504 — frame-pipeline geometry through seam S4
 * (mobile/mobile_test_targets.yaml). Pure arithmetic; the bitmap wrappers
 * call exactly these functions, so the numbers here are the numbers on the
 * wire path.
 */
class ImageTransformGeometryTest {

    // ── MU-501 · landscape halves geometry ──────────────────────────────

    @Test
    fun mu501_landscapeHalfScalesTo256x288() {
        // 1280x720 split -> 640x720 half; shortest edge (width) to 256.
        assertEquals(256 to 288, ImageTransform.scaledDimsShortestEdge256(640, 720))
    }

    @Test
    fun mu501_landscapeCropRectIs16_32_224_224() {
        assertEquals(16 to 32, ImageTransform.centerCrop224Origin(256, 288))
    }

    @Test
    fun mu501_landscapeVerticalRetention() {
        // 224 of 288 scaled rows survive the landscape crop: 77.8 %.
        assertEquals(224.0 / 288.0, 0.7778, 0.0001)
    }

    // ── MU-502 · D2 pin: portrait halves keep 24.6 % vertical FOV ───────

    @Test
    fun mu502_d2_portraitHalfScalesTo256x910() {
        // Portrait post-rotation 720x1280 split -> 360x1280 half.
        assertEquals(256 to 910, ImageTransform.scaledDimsShortestEdge256(360, 1280))
    }

    @Test
    fun mu502_d2_portraitCropStartsAtRow343() {
        assertEquals(16 to 343, ImageTransform.centerCrop224Origin(256, 910))
    }

    @Test
    fun mu502_d2_portraitVerticalRetentionIs24point6Percent() {
        // The documented defect D2: only 224 of 910 scaled rows survive —
        // 24.6 % of the vertical field of view (deliberately unfixed; pin).
        val retention = 224.0 / 910.0
        assertEquals(0.2462, retention, 0.0001)
    }

    // ── MU-504 · letterbox geometry (speech/pixel path) ─────────────────

    @Test
    fun mu504_landscapeLetterboxContentIs224x126() {
        assertEquals(224 to 126, ImageTransform.letterboxContentDims(1280, 720))
    }

    @Test
    fun mu504_greyBarsAre49RowsEachSide() {
        val (_, h) = ImageTransform.letterboxContentDims(1280, 720)
        assertEquals(49, (224 - h) / 2)
    }

    @Test
    fun mu504_portraitLetterboxContentIs126x224() {
        assertEquals(126 to 224, ImageTransform.letterboxContentDims(720, 1280))
    }

    @Test
    fun mu504_squareInputFillsTheSquare() {
        assertEquals(224 to 224, ImageTransform.letterboxContentDims(500, 500))
    }

    @Test
    fun mu504_degenerateDimsClampToOne() {
        // A 1-px-high frame still letterboxes without a zero dimension.
        assertEquals(224 to 1, ImageTransform.letterboxContentDims(1280, 1))
    }
}
