package com.chastechgroup.camix.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GlMathTest {

    private fun m2(rot: Int): FloatArray {
        val m = GlMath.cameraToUpright(1920, 1080, rot)
        // 2-D part (a,b) -> (x,y) of the column-major 4x4:  [m00 m01; m10 m11]
        return floatArrayOf(m[0], m[4], m[1], m[5])
    }

    @Test
    fun rotationMatricesMatchClockwiseQuarterTurns() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), m2(0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f), m2(90), 1e-6f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, -1f), m2(180), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, -1f, 1f, 0f), m2(270), 1e-6f)
    }

    @Test
    fun uprightSizeSwapsForQuarterTurns() {
        assertArrayEquals(intArrayOf(1080, 1920), GlMath.uprightSize(1920, 1080, 90))
        assertArrayEquals(intArrayOf(1920, 1080), GlMath.uprightSize(1920, 1080, 180))
    }

    @Test
    fun centreCropScalesTheShortAxisOfTheOverflow() {
        val tall = GlMath.cropScale(1080, 1920, 1080, 2340)      // view taller than the frame
        assertEquals(1f, tall[1], 1e-6f)
        assertEquals(true, tall[0] > 1f)
        val wide = GlMath.cropScale(1080, 1920, 1080, 1200)      // view wider than the frame
        assertEquals(1f, wide[0], 1e-6f)
        assertEquals(true, wide[1] > 1f)
    }
}
