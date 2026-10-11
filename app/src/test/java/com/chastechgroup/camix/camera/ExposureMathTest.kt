package com.chastechgroup.camix.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

/** Closed-loop simulation of the exposure controller against a lagging camera-AE plant. */
class ExposureMathTest {

    private class Run(val finalBrightness: Double, val finalIndex: Int, val changesInSecondHalf: Int)

    private fun simulate(sceneBrightness: Double, seconds: Double = 20.0): Run {
        val step = 1.0 / 3.0
        val loop = ExposureMath.Loop(step, -12, 12)
        var idx = 0
        var evEff = 0.0
        var b = sceneBrightness
        val history = ArrayList<Int>()
        var t = 0.0
        while (t < seconds) {
            repeat(10) { evEff += (idx * step - evEff) * (0.035 / 0.5) }          // camera AE lag
            val bTrue = min(255.0, sceneBrightness * 2.0.pow(evEff / 2.2))
            b = 0.18 * bTrue + 0.82 * b
            idx = loop.next(idx, 130.0, b)
            history.add(idx)
            t += 0.35
        }
        val tail = history.subList(history.size / 2, history.size)
        var changes = 0
        for (i in 1 until tail.size) if (tail[i] != tail[i - 1]) changes++
        return Run(b, idx, changes)
    }

    @Test
    fun convergesWithoutHuntingInUsableScenes() {
        for (scene in doubleArrayOf(70.0, 100.0, 150.0, 200.0, 240.0)) {
            val r = simulate(scene)
            assertTrue("scene $scene -> brightness ${r.finalBrightness}",
                r.finalBrightness in 108.0..152.0)
            assertTrue("scene $scene hunting: ${r.changesInSecondHalf} index changes", r.changesInSecondHalf <= 1)
        }
    }

    @Test
    fun veryDarkScenesStopAtPlusTwoEv() {
        val r = simulate(30.0)
        assertEquals(6, r.finalIndex)          // 2 EV / (1/3 EV per index)
    }

    @Test
    fun deadBandIgnoresSmallErrors() {
        val loop = ExposureMath.Loop(1.0 / 3.0, -12, 12)
        assertEquals(4, loop.next(4, 130.0, 128.0))
    }

    @Test
    fun errorIsPositiveWhenTooDark() {
        assertTrue(ExposureMath.errorEv(130.0, 60.0) > 1.0)
        assertTrue(ExposureMath.errorEv(130.0, 200.0) < -0.5)
    }
}
