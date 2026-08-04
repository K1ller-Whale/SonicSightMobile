package com.k1llerwhale.sonicsight.util

/**
 * The app's signature colormap: magma, the vernacular of spectrograms.
 *
 * Chosen over the previous jet map for two load-bearing reasons:
 * - Perceptually uniform with monotonically increasing lightness, so the
 *   ordering (quiet -> loud) survives the common red-green colour-vision
 *   deficiencies — the information rides lightness, not hue opposition.
 *   A red-green heatmap in an app about making sound visible would be a
 *   poor joke; jet is exactly that.
 * - Its dark low end disappears over the camera image instead of hazing it,
 *   so only genuine signal paints the scene.
 */
object MagmaPalette {

    // Anchor stops of matplotlib's magma, evenly spaced in [0, 1].
    private val STOPS = intArrayOf(
        0xFF000004.toInt(), // 0.000
        0xFF2C115F.toInt(), // 0.125
        0xFF51127C.toInt(), // 0.250
        0xFF822681.toInt(), // 0.375
        0xFFB63679.toInt(), // 0.500
        0xFFE65164.toInt(), // 0.625
        0xFFFB8861.toInt(), // 0.750
        0xFFFEC287.toInt(), // 0.875
        0xFFFCFDBF.toInt(), // 1.000
    )

    /** Opaque ARGB colour for a normalized value in [0, 1]. */
    fun color(v: Float): Int {
        val x = v.coerceIn(0f, 1f) * (STOPS.size - 1)
        val i = x.toInt().coerceAtMost(STOPS.size - 2)
        val t = x - i
        return lerp(STOPS[i], STOPS[i + 1], t)
    }

    /** The anchor colours, low to high — for drawing legend gradients. */
    fun stops(): IntArray = STOPS.copyOf()

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
