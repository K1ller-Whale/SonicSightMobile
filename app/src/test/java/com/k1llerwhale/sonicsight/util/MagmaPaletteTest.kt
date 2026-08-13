package com.k1llerwhale.sonicsight.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MU-606 — MagmaPalette properties (mobile/mobile_test_targets.yaml MU-606).
 * The ordering claim the palette makes (quiet -> loud rides lightness, not
 * hue) is load-bearing for colour-vision-deficient users; monotone
 * lightness is therefore pinned, not assumed.
 */
class MagmaPaletteTest {

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    @Test
    fun mu606_nineAnchorStopsWithMagmaEndpoints() {
        val stops = MagmaPalette.stops()
        assertEquals(9, stops.size)
        assertEquals(0xFF000004.toInt(), stops.first())
        assertEquals(0xFFFCFDBF.toInt(), stops.last())
    }

    @Test
    fun mu606_endpointColorsMatchStops() {
        assertEquals(MagmaPalette.stops().first(), MagmaPalette.color(0f))
        assertEquals(MagmaPalette.stops().last(), MagmaPalette.color(1f))
    }

    @Test
    fun mu606_outOfRangeInputClamps() {
        assertEquals(MagmaPalette.color(0f), MagmaPalette.color(-1f))
        assertEquals(MagmaPalette.color(1f), MagmaPalette.color(2f))
        assertEquals(MagmaPalette.color(1f), MagmaPalette.color(Float.MAX_VALUE))
    }

    @Test
    fun mu606_alwaysOpaque() {
        for (i in 0..256) {
            val v = i / 256f
            assertEquals("alpha at v=$v", 0xFF, (MagmaPalette.color(v) ushr 24))
        }
    }

    @Test
    fun mu606_anchorLuminanceStrictlyIncreasing() {
        // Per-channel interpolation is linear, so luminance between anchors is
        // linear too: strictly increasing anchors prove monotone ordering for
        // the whole ramp (up to per-channel int truncation, checked below).
        val stops = MagmaPalette.stops()
        for (i in 1 until stops.size) {
            assertTrue("anchor $i luminance not increasing",
                luminance(stops[i]) > luminance(stops[i - 1]))
        }
    }

    @Test
    fun mu606_sampledRampLuminanceMonotoneWithinTruncationJitter() {
        // Truncation in the integer lerp can jitter each channel by <= 1,
        // so luminance may locally dip by at most 0.299+0.587+0.114 = 1.0.
        var prev = -1.0
        for (i in 0..512) {
            val lum = luminance(MagmaPalette.color(i / 512f))
            assertTrue("luminance dip at step $i: $lum after $prev", lum >= prev - 1.0)
            prev = maxOf(prev, lum)
        }
    }

    @Test
    fun mu606_stopsReturnsDefensiveCopy() {
        val a = MagmaPalette.stops()
        a[0] = 0
        assertArrayEquals(MagmaPalette.stops(), MagmaPalette.stops())
        assertEquals(0xFF000004.toInt(), MagmaPalette.stops().first())
    }
}
