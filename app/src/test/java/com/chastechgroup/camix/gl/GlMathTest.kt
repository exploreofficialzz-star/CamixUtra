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
    fun portraitPhoneNeedsNoExtraRotation() {
        // back camera: sensor 90, target rotation 0 -> CameraX reports 90; the SurfaceTexture matrix already has it
        val back = GlMath.plan(1920, 1080, 90, 90)
        assertEquals(0, back.remainingDegrees)
        assertEquals(1080, back.naturalW); assertEquals(1920, back.naturalH)
        assertEquals(1080, back.uprightW); assertEquals(1920, back.uprightH)
        // front camera: sensor 270
        val front = GlMath.plan(1920, 1080, 270, 270)
        assertEquals(0, front.remainingDegrees)
        assertEquals(1080, front.uprightW); assertEquals(1920, front.uprightH)
    }

    @Test
    fun landscapeDisplayAddsOnlyTheDisplayRotation() {
        // back camera, display rotated 90 deg: CameraX reports 0, the matrix still contains the sensor 90
        val p = GlMath.plan(1920, 1080, 0, 90)
        assertEquals(270, p.remainingDegrees)
        assertEquals(1920, p.uprightW); assertEquals(1080, p.uprightH)
    }

    @Test
    fun tapOnTheUprightFrameMapsToTheSensorBuffer() {
        // portrait phone, back camera (sensor 90): buffer bottom-left shows up at the frame's top-left
        assertArrayEquals(floatArrayOf(0f, 1f), GlMath.frameToBuffer(0f, 0f, 0, 90), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), GlMath.frameToBuffer(1f, 0f, 0, 90), 1e-6f)   // frame top-right
        assertArrayEquals(floatArrayOf(1f, 0f), GlMath.frameToBuffer(1f, 1f, 0, 90), 1e-6f)   // frame bottom-right
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), GlMath.frameToBuffer(0.5f, 0.5f, 0, 90), 1e-6f)
        // front camera (sensor 270): frame top-left is the buffer's top-right
        assertArrayEquals(floatArrayOf(1f, 0f), GlMath.frameToBuffer(0f, 0f, 0, 270), 1e-6f)
        // no rotation at all
        assertArrayEquals(floatArrayOf(0.25f, 0.75f), GlMath.frameToBuffer(0.25f, 0.75f, 0, 0), 1e-6f)
    }

    @Test
    fun undoRotationIsTheInverseOfACwQuarterTurn() {
        // rotating the point (x,y) clockwise by 90 sends it to (1-y, x); undoing must return it
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val x = 0.2f; val y = 0.7f
            var u = x; var v = y
            for (i in 0 until deg / 90) { val nu = 1f - v; val nv = u; u = nu; v = nv }   // forward CW turns
            assertArrayEquals(floatArrayOf(x, y), GlMath.undoRotation(u, v, deg), 1e-6f)
        }
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
