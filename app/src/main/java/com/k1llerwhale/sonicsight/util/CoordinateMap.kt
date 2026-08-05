package com.k1llerwhale.sonicsight.util

/**
 * The coordinate chain for touch mode, built as pure math so it is
 * unit-testable on the JVM (PIXEL_PLAN section 6):
 *
 *   view touch -> (inverse of PreviewView fillCenter) -> camera frame space
 *              -> (letterboxAndCompress geometry)     -> 224x224 letterbox space
 *              -> normalized [0,1] query coordinates  -> feature grid cell
 *
 * This replaces the old implicit fitXY-over-fillCenter approximation, which
 * was tolerable for a glow overlay and is not tolerable for touch. An
 * off-by-one here feels "subtly broken" and is very hard to debug later —
 * hence the tests.
 */
object CoordinateMap {

    data class Norm(val x: Float, val y: Float)

    /**
     * View coordinates -> camera-frame coordinates, inverting PreviewView's
     * fillCenter (center-crop) mapping: the frame is uniformly scaled with
     * scale = max(viewW/frameW, viewH/frameH) and centered; edges are cropped.
     * Returns null when the touch maps outside the frame (cannot happen for
     * fillCenter, but guarded for safety).
     */
    fun viewToFrame(
        viewX: Float, viewY: Float,
        viewW: Float, viewH: Float,
        frameW: Float, frameH: Float,
    ): Norm? {
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) return null
        val scale = maxOf(viewW / frameW, viewH / frameH)
        val dispW = frameW * scale
        val dispH = frameH * scale
        val offX = (viewW - dispW) / 2f
        val offY = (viewH - dispH) / 2f
        val fx = (viewX - offX) / scale
        val fy = (viewY - offY) / scale
        if (fx < 0 || fy < 0 || fx > frameW || fy > frameH) return null
        return Norm(fx, fy)
    }

    /**
     * Camera-frame coordinates -> normalized letterbox-space coordinates,
     * mirroring ImageTransform.letterboxAndCompress exactly: uniform scale
     * dim/max(frameW, frameH), centered on a dim x dim canvas. The result is
     * in [0,1] over the FULL letterboxed square — including the grey bars,
     * which is what the proto contract specifies. A touch can only land in
     * the content region because the preview shows only content.
     */
    fun frameToLetterboxNorm(
        frameX: Float, frameY: Float,
        frameW: Float, frameH: Float,
        dim: Float = 224f,
    ): Norm {
        val scale = dim / maxOf(frameW, frameH)
        val w = frameW * scale
        val h = frameH * scale
        val offX = (dim - w) / 2f
        val offY = (dim - h) / 2f
        val lx = (frameX * scale + offX) / dim
        val ly = (frameY * scale + offY) / dim
        return Norm(lx.coerceIn(0f, 1f), ly.coerceIn(0f, 1f))
    }

    /** The full chain: view touch -> normalized letterbox query coordinates. */
    fun viewToQueryNorm(
        viewX: Float, viewY: Float,
        viewW: Float, viewH: Float,
        frameW: Float, frameH: Float,
        dim: Float = 224f,
    ): Norm? {
        val f = viewToFrame(viewX, viewY, viewW, viewH, frameW, frameH) ?: return null
        return frameToLetterboxNorm(f.x, f.y, frameW, frameH, dim)
    }

    /** Normalized letterbox coordinates -> feature-grid cell (col, row). */
    fun normToCell(xNorm: Float, yNorm: Float, gridW: Int, gridH: Int): Pair<Int, Int> {
        val col = (xNorm * gridW).toInt().coerceIn(0, gridW - 1)
        val row = (yNorm * gridH).toInt().coerceIn(0, gridH - 1)
        return col to row
    }
}
