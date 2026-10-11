package com.chastechgroup.camix.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class BeatDetectorTest {

    private val sr = 44100

    /** kick drum every beat + off-beat hi-hats + a little room noise */
    private fun drumLoop(bpm: Int, seconds: Int): FloatArray {
        val n = sr * seconds
        val rnd = Random(1)
        val x = DoubleArray(n) { rnd.nextGaussian() * 0.01 }
        val period = 60.0 / bpm
        var t = 0.5
        while (t < seconds - 0.3) {
            val i = (t * sr).toInt()
            val m = (0.12 * sr).toInt()
            for (k in 0 until m) {
                if (i + k >= n) break
                val tt = k.toDouble() / sr
                x[i + k] += 0.6 * sin(2.0 * PI * (55.0 + 90.0 * exp(-tt * 35.0)) * tt) * exp(-tt * 28.0)
            }
            val j = ((t + period / 2) * sr).toInt()
            val hm = (0.03 * sr).toInt()
            for (k in 0 until hm) {
                if (j + k >= n) break
                x[j + k] += 0.12 * rnd.nextGaussian() * exp(-k / (0.008 * sr))
            }
            t += period
        }
        return FloatArray(n) { x[it].toFloat() }
    }

    @Test
    fun findsKicksAndTempoOfA120BpmLoop() {
        val x = drumLoop(120, 20)
        val d = BeatDetector(sr)
        var kicks = 0
        val chunk = FloatArray(1024)
        for (off in 0 until x.size - 1023 step 1024) {
            System.arraycopy(x, off, chunk, 0, 1024)
            if (d.process(chunk, 1024).kick > 0f) kicks++
        }
        // ground truth: 39 kicks
        assertTrue("kicks detected = $kicks", kicks in 34..45)
        assertEquals(120.0, d.bpm.toDouble(), 7.0)
    }

    @Test
    fun roomNoiseTriggersNothing() {
        val rnd = Random(9)
        val x = FloatArray(sr * 10) { (rnd.nextGaussian() * 0.02).toFloat() }
        val d = BeatDetector(sr)
        var events = 0
        val chunk = FloatArray(1024)
        for (off in 0 until x.size - 1023 step 1024) {
            System.arraycopy(x, off, chunk, 0, 1024)
            val o = d.process(chunk, 1024)
            if (o.kick > 0f || o.hit > 0f) events++
        }
        assertTrue("false onsets in 10 s of noise = $events", events <= 2)
    }
}
