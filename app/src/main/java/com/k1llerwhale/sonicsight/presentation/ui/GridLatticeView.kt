package com.k1llerwhale.sonicsight.presentation.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The sounding lattice — touch mode's signature element.
 *
 * Draws the model's TRUE feature grid (14 columns x the content rows) as a
 * whisper-thin lattice over the viewfinder, and lights a tapped CELL as an
 * ember-stroked square. Deliberately never a crosshair or a ripple: the
 * model resolves 196 cells, not 50,176 pixels, and the interface's precision
 * language must match the model's ("structure is information"). The grid is
 * static; only cells respond — restraint lives here on purpose.
 *
 * Never consumes touches; the overlay beneath owns interaction.
 */
class GridLatticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30F4F2EF          // chalk at ~19% — whisper, not wireframe
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFB8861.toInt()  // ember — the one interactive accent
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // Affine from grid coordinates to view pixels (set by the activity from
    // the same math that registers the energy overlay).
    private var originX = 0f
    private var originY = 0f
    private var cellSize = 0f
    private var gridW = 14
    private var rowTop = 3      // first content row (inclusive)
    private var rowBottom = 10  // last content row (inclusive)

    private var tappedCol = -1
    private var tappedRow = -1
    private var cellAlpha = 0f
    private var animator: ValueAnimator? = null

    // The followed (sticky) cell stays lit in glow while the live stream
    // plays its region.
    private var stickyCol = -1
    private var stickyRow = -1
    private val stickyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFCFDBF.toInt()  // glow — same voice as the soloed dot ring
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    fun setStickyCell(col: Int, row: Int) {
        stickyCol = col; stickyRow = row; invalidate()
    }

    fun clearStickyCell() {
        stickyCol = -1; stickyRow = -1; invalidate()
    }

    fun setGridTransform(
        originX: Float, originY: Float, cellSize: Float,
        gridW: Int, rowTop: Int, rowBottom: Int,
    ) {
        this.originX = originX
        this.originY = originY
        this.cellSize = cellSize
        this.gridW = gridW
        this.rowTop = rowTop
        this.rowBottom = rowBottom
        invalidate()
    }

    /** Light the tapped cell; fades out (or, under reduced motion, holds
     *  briefly then clears — no animation). */
    fun showTappedCell(col: Int, row: Int, reducedMotion: Boolean) {
        tappedCol = col
        tappedRow = row
        animator?.cancel()
        if (reducedMotion) {
            cellAlpha = 1f
            invalidate()
            postDelayed({ cellAlpha = 0f; invalidate() }, 800)
        } else {
            animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 800
                addUpdateListener { cellAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize <= 0f) return

        val top = originY + rowTop * cellSize
        val bottom = originY + (rowBottom + 1) * cellSize
        // Vertical boundaries: 0..gridW
        for (c in 0..gridW) {
            val x = originX + c * cellSize
            canvas.drawLine(x, top, x, bottom, linePaint)
        }
        // Horizontal boundaries across the content rows only — the grey
        // letterbox rows are not scene and get no lattice.
        for (r in rowTop..(rowBottom + 1)) {
            val y = originY + r * cellSize
            canvas.drawLine(originX, y, originX + gridW * cellSize, y, linePaint)
        }

        if (cellAlpha > 0f && tappedCol >= 0) {
            cellPaint.alpha = (cellAlpha * 255).toInt()
            val l = originX + tappedCol * cellSize
            val t = originY + tappedRow * cellSize
            canvas.drawRect(l + 1.5f, t + 1.5f, l + cellSize - 1.5f, t + cellSize - 1.5f, cellPaint)
        }

        if (stickyCol >= 0) {
            val l = originX + stickyCol * cellSize
            val t = originY + stickyRow * cellSize
            canvas.drawRect(l + 1.5f, t + 1.5f, l + cellSize - 1.5f, t + cellSize - 1.5f, stickyPaint)
        }
    }
}
