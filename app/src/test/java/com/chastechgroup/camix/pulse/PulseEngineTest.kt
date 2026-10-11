package com.chastechgroup.camix.pulse

import com.chastechgroup.camix.audio.AudioFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseEngineTest {

    private fun kick(strength: Float) = AudioFeatures(0.3f, 0.9f, 0.3f, 0.2f, strength, 0f, 120f, 0.9f)

    @Test
    fun disabledEngineIsNeutral() {
        val e = PulseEngine()
        e.onFeatures(kick(1f))
        assertEquals(PulseParams.NONE, e.step(0.016f))
    }

    @Test
    fun kickPunchesInThenDecays() {
        val e = PulseEngine()
        e.enabled = true
        e.style = PulseStyle.PUNCH
        e.intensity = 1f
        e.onFeatures(kick(1f))
        val first = e.step(1f / 30f)
        assertTrue("zoom after kick = ${first.zoom}", first.zoom > 1.05f)
        assertTrue(first.zoom < 1.2f)

        var last = first
        for (i in 0 until 90) last = e.step(1f / 30f)       // 3 s later
        assertEquals(1f, last.zoom, 0.005f)
    }

    @Test
    fun styleSelectsTheVisualChannel() {
        val e = PulseEngine()
        e.enabled = true
        e.intensity = 1f

        e.style = PulseStyle.SPLIT
        e.onFeatures(kick(1f))
        val split = e.step(1f / 30f)
        assertTrue(split.rgb > 0.005f)
        assertEquals(0f, split.flash, 1e-6f)

        e.reset()
        e.style = PulseStyle.STROBE
        e.onFeatures(kick(1f))
        val strobe = e.step(1f / 30f)
        assertTrue(strobe.flash > 0.1f)
        assertEquals(0f, strobe.rgb, 1e-6f)
    }
}
