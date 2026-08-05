package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The touch coordinate chain, verified against hand-computed geometry.
 * Scene: 1280x720 landscape camera frame (the locked configuration).
 */
class CoordinateMapTest {

    // A 20:9 phone in landscape: 2400x1080 view showing a 16:9 frame via
    // fillCenter -> scale = max(2400/1280, 1080/720) = 1.875,
    // displayed 2400x1350, cropped 135 px top and bottom (offY = -135).
    private val VW = 2400f; private val VH = 1080f
    private val FW = 1280f; private val FH = 720f

    @Test
    fun viewCenterMapsToFrameCenter() {
        val f = CoordinateMap.viewToFrame(1200f, 540f, VW, VH, FW, FH)!!
        assertEquals(640f, f.x, 0.01f)
        assertEquals(360f, f.y, 0.01f)
    }

    @Test
    fun fillCenterCropIsInverted() {
        // Top-left of the VIEW is not the top-left of the FRAME: 135 view px
        // are cropped above -> frame y = 135/1.875 = 72.
        val f = CoordinateMap.viewToFrame(0f, 0f, VW, VH, FW, FH)!!
        assertEquals(0f, f.x, 0.01f)
        assertEquals(72f, f.y, 0.01f)
    }

    @Test
    fun frameCenterIsLetterboxCenter() {
        val n = CoordinateMap.frameToLetterboxNorm(640f, 360f, FW, FH)
        assertEquals(0.5f, n.x, 1e-4f)
        assertEquals(0.5f, n.y, 1e-4f)
    }

    @Test
    fun letterboxGeometryMatchesImageTransform() {
        // 1280x720 -> scale 224/1280 = 0.175 -> content 224x126 at y offset 49.
        // Frame top edge (y=0) => letterbox y = 49/224.
        val top = CoordinateMap.frameToLetterboxNorm(0f, 0f, FW, FH)
        assertEquals(0f, top.x, 1e-4f)
        assertEquals(49f / 224f, top.y, 1e-4f)
        // Frame bottom edge => (49 + 126)/224.
        val bottom = CoordinateMap.frameToLetterboxNorm(1280f, 720f, FW, FH)
        assertEquals(1f, bottom.x, 1e-4f)
        assertEquals(175f / 224f, bottom.y, 1e-4f)
    }

    @Test
    fun fullChainCenterAndCells() {
        val n = CoordinateMap.viewToQueryNorm(1200f, 540f, VW, VH, FW, FH)!!
        assertEquals(0.5f, n.x, 1e-3f)
        assertEquals(0.5f, n.y, 1e-3f)
        assertEquals(7 to 7, CoordinateMap.normToCell(n.x, n.y, 14, 14))
    }

    @Test
    fun contentEdgesLandInLiveCellRows() {
        // Letterboxed content occupies grid rows 3..10 (PIXEL_PLAN D3).
        val top = CoordinateMap.viewToQueryNorm(1200f, 0f, VW, VH, FW, FH)!!
        val (_, topRow) = CoordinateMap.normToCell(top.x, top.y, 14, 14)
        assert(topRow in 3..10)
        val bottom = CoordinateMap.viewToQueryNorm(1200f, VH, VW, VH, FW, FH)!!
        val (_, bottomRow) = CoordinateMap.normToCell(bottom.x, bottom.y, 14, 14)
        assert(bottomRow in 3..10)
        assert(topRow < bottomRow)
    }

    @Test
    fun cellIndicesClampAtEdges() {
        assertEquals(0 to 0, CoordinateMap.normToCell(0f, 0f, 14, 14))
        assertEquals(13 to 13, CoordinateMap.normToCell(1f, 1f, 14, 14))
    }

    @Test
    fun matchingAspectRatioIsIdentityScale() {
        // View exactly 16:9: no crop anywhere.
        val f = CoordinateMap.viewToFrame(0f, 0f, 1920f, 1080f, FW, FH)
        assertNotNull(f)
        assertEquals(0f, f!!.x, 0.01f)
        assertEquals(0f, f.y, 0.01f)
    }
}
