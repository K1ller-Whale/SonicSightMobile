package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MU-603 / MU-604 — touch-chain cell boundaries, portrait frames and
 * non-square grids (mobile/mobile_test_targets.yaml). Complements the
 * existing CoordinateMapTest (landscape 1280x720 chain), which left exactly
 * these gaps open — its own doc names interior boundary truncation as the
 * "subtly broken" risk.
 */
class CoordinateMapBoundaryTest {

    // ── MU-603 · interior cell boundaries on the 14x14 grid ─────────────

    @Test
    fun mu603_pointsJustInsideEachCellMapToThatCell() {
        val eps = 1e-4f
        for (k in 0 until 14) {
            val lo = k / 14f + eps
            val hi = (k + 1) / 14f - eps
            assertEquals("just above boundary $k/14", k to k,
                CoordinateMap.normToCell(lo, lo, 14, 14))
            assertEquals("just below boundary ${k + 1}/14", k to k,
                CoordinateMap.normToCell(hi, hi, 14, 14))
        }
    }

    @Test
    fun mu603_exactFloatBoundariesLandInUpperCell_characterization() {
        // Characterization of float behaviour at the exact representable
        // boundary value k/14f: for every k on this grid, fl(fl(k/14)*14)
        // rounds to >= k, so the boundary point lands in cell k (the upper
        // cell), never k-1. Pinned so a refactor to double or a different
        // rounding mode shows up as a diff here.
        for (k in 1 until 14) {
            val boundary = k / 14f
            assertEquals("boundary $k/14f", k to k,
                CoordinateMap.normToCell(boundary, boundary, 14, 14))
        }
    }

    @Test
    fun mu603_cornersAndClampAtOne() {
        assertEquals(0 to 0, CoordinateMap.normToCell(0f, 0f, 14, 14))
        assertEquals(13 to 13, CoordinateMap.normToCell(1f, 1f, 14, 14))
        assertEquals(13 to 0, CoordinateMap.normToCell(1f, 0f, 14, 14))
        assertEquals(0 to 13, CoordinateMap.normToCell(0f, 1f, 14, 14))
    }

    // ── MU-604 · portrait frame chain ───────────────────────────────────

    @Test
    fun mu604_portraitCenterMapsToGridCenter() {
        // Post-rotation portrait frame 720x1280 in a 1080x2400 view.
        val n = CoordinateMap.viewToQueryNorm(540f, 1200f, 1080f, 2400f, 720f, 1280f)!!
        assertEquals(0.5f, n.x, 1e-6f)
        assertEquals(0.5f, n.y, 1e-6f)
        assertEquals(7 to 7, CoordinateMap.normToCell(n.x, n.y, 14, 14))
    }

    @Test
    fun mu604_portraitLetterboxGeometry() {
        // Portrait 720x1280: scale = 224/1280 = 0.175, content 126x224,
        // grey bars 49 px left/right (the landscape geometry rotated).
        val left = CoordinateMap.frameToLetterboxNorm(0f, 640f, 720f, 1280f)
        val right = CoordinateMap.frameToLetterboxNorm(720f, 640f, 720f, 1280f)
        assertEquals(49f / 224f, left.x, 1e-6f)
        assertEquals(175f / 224f, right.x, 1e-6f)
        assertEquals(0.5f, left.y, 1e-6f)

        val top = CoordinateMap.frameToLetterboxNorm(360f, 0f, 720f, 1280f)
        val bottom = CoordinateMap.frameToLetterboxNorm(360f, 1280f, 720f, 1280f)
        assertEquals(0f, top.y, 1e-6f)
        assertEquals(1f, bottom.y, 1e-6f)
    }

    @Test
    fun mu604_portraitViewTopCenterFullChain() {
        // View 1080x2400 over frame 720x1280: fillCenter scale =
        // max(1080/720, 2400/1280) = 1.875, so 135 px are cropped off each
        // horizontal side of the displayed frame. View top-center (540, 0)
        // -> frame (360, 0) -> letterbox (0.5, 0) -> cell (7, 0).
        val f = CoordinateMap.viewToFrame(540f, 0f, 1080f, 2400f, 720f, 1280f)!!
        assertEquals(360f, f.x, 1e-4f)
        assertEquals(0f, f.y, 1e-4f)
        val n = CoordinateMap.frameToLetterboxNorm(f.x, f.y, 720f, 1280f)
        assertEquals(0.5f, n.x, 1e-6f)
        assertEquals(0f, n.y, 1e-6f)
        assertEquals(7 to 0, CoordinateMap.normToCell(n.x, n.y, 14, 14))
    }

    // ── MU-604 · non-square grid ────────────────────────────────────────

    @Test
    fun mu604_nonSquareGridMapsAxesIndependently() {
        assertEquals(7 to 3, CoordinateMap.normToCell(0.5f, 0.5f, 14, 7))
        assertEquals(13 to 6, CoordinateMap.normToCell(1f, 1f, 14, 7))
        assertEquals(0 to 0, CoordinateMap.normToCell(0f, 0f, 14, 7))
        // y boundary on the 7-row axis: 3/7 + eps -> row 3.
        assertEquals(0 to 3, CoordinateMap.normToCell(0f, 3f / 7f + 1e-4f, 14, 7))
    }

    // ── MU-603 · guard paths ────────────────────────────────────────────

    @Test
    fun mu603_invalidDimensionsReturnNull() {
        assertNull(CoordinateMap.viewToFrame(0f, 0f, 0f, 2400f, 720f, 1280f))
        assertNull(CoordinateMap.viewToFrame(0f, 0f, 1080f, 0f, 720f, 1280f))
        assertNull(CoordinateMap.viewToFrame(0f, 0f, 1080f, 2400f, 0f, 1280f))
        assertNull(CoordinateMap.viewToFrame(0f, 0f, 1080f, 2400f, 720f, 0f))
        assertNull(CoordinateMap.viewToQueryNorm(0f, 0f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun mu603_touchBeyondFrameEdgeReturnsNull() {
        // With matching aspect (no crop), a view x beyond the right edge
        // maps outside the frame and must be rejected, not clamped.
        assertNull(CoordinateMap.viewToFrame(1921f, 500f, 1920f, 1080f, 1280f, 720f))
    }
}
