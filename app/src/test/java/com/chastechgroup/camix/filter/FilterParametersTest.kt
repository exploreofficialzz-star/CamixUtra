package com.chastechgroup.camix.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParametersTest {

    @Test
    fun defaultLookIsNeutral() {
        assertTrue(FilterParameters.DEFAULT.isNeutral())
        assertTrue(FilterPreset.NONE.toParameters().isNeutral())
    }

    @Test
    fun presetsAreNotNeutralAndOnlyAutoKeepsAutoEnabled() {
        for (p in FilterPreset.entries) {
            val params = p.toParameters()
            if (p == FilterPreset.NONE || p == FilterPreset.AUTO) continue
            assertFalse("${p.name} must not be neutral", params.isNeutral())
            assertFalse("${p.name} must switch AI Auto off", params.isAutoEnabled)
        }
        assertTrue(FilterPreset.AUTO.toParameters().isAutoEnabled)
    }

    @Test
    fun blendMovesTowardsTheTargetAndTakesStyleImmediately() {
        val a = FilterParameters.DEFAULT
        val b = FilterParameters.NIGHT
        val mid = a.blendTo(b, 0.5f)
        assertEquals(b.brightness * 0.5f, mid.brightness, 1e-6f)
        assertEquals(StyleMode.NIGHT, mid.style)
        val done = a.blendTo(b, 1f)
        assertEquals(b.exposure, done.exposure, 1e-6f)
    }
}
