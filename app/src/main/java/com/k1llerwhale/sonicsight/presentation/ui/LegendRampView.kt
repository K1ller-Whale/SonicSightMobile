package com.k1llerwhale.sonicsight.presentation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.k1llerwhale.sonicsight.util.MagmaPalette

/**
 * Thin vertical ramp showing the heatmap's colour scale, high at the top.
 * The labels around it (set in the layout) say what high and low MEAN for
 * the active model — per-pixel sound energy vs audio-video match.
 */
class LegendRampView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // High value at the top: reverse the low-to-high stops.
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            MagmaPalette.stops().reversedArray(), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = width / 2f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
