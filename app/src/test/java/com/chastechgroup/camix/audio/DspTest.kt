package com.chastechgroup.camix.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private val sr = 44100

    private fun sine(freq: Double, n: Int, amp: Double = 0.5): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sr)).toFloat() }

    private fun peakDb(buf: FloatArray, from: Int): Double {
        var m = 0f
        for (i in from until buf.size) m = max(m, abs(buf[i]))
        return 20.0 * log10(m / 0.5)
    }

    @Test
    fun highpassCutsRumbleAndKeepsSpeech() {
        val low = sine(30.0, sr)
        Biquad.highpass(sr, 120.0).process(low, low.size)
        assertTrue("30 Hz should be strongly attenuated", peakDb(low, sr / 2) < -15.0)

        val mid = sine(1000.0, sr)
        Biquad.highpass(sr, 120.0).process(mid, mid.size)
        assertEquals(0.0, peakDb(mid, sr / 2), 0.3)
    }

    @Test
    fun biquadStateSurvivesBufferBoundaries() {
        val rnd = Random(3)
        val x = FloatArray(8192) { (rnd.nextGaussian() * 0.3).toFloat() }
        val whole = x.copyOf()
        Biquad.highpass(sr, 120.0).process(whole, whole.size)

        val parts = x.copyOf()
        val f = Biquad.highpass(sr, 120.0)
        val chunk = FloatArray(1024)
        for (off in 0 until 8192 step 1024) {
            System.arraycopy(parts, off, chunk, 0, 1024)
            f.process(chunk, 1024)
            System.arraycopy(chunk, 0, parts, off, 1024)
        }
        for (i in 0 until 8192) assertEquals(whole[i].toDouble(), parts[i].toDouble(), 1e-5)
    }

    @Test
    fun presenceEqBoostsThreeKilohertz() {
        val s = sine(3000.0, sr, 0.25)
        Biquad.peaking(sr, 3000.0, 3.0).process(s, s.size)
        var m = 0f
        for (i in sr / 2 until s.size) m = max(m, abs(s[i]))
        assertEquals(3.0, 20.0 * log10(m / 0.25), 0.4)
    }

    @Test
    fun softLimiterNeverExceedsFullScale() {
        val x = floatArrayOf(0.2f, 0.8f, 1.5f, -3f, 10f, -0.5f)
        SoftLimiter.process(x, x.size)
        for (v in x) assertTrue("limiter output $v", abs(v) <= 1.0f)
        assertEquals(0.2f, x[0], 1e-6f)          // transparent below the knee
    }

    @Test
    fun denoiserReducesStationaryNoiseAndStaysFinite() {
        val rnd = Random(5)
        val n = sr * 6
        val x = FloatArray(n) { (rnd.nextGaussian() * 0.05).toFloat() }
        val y = x.copyOf()
        val dn = SpectralDenoiser()
        for (off in 0 until n - 1023 step 1024) {
            val chunk = FloatArray(1024)
            System.arraycopy(y, off, chunk, 0, 1024)
            dn.process(chunk, 1024)
            System.arraycopy(chunk, 0, y, off, 1024)
        }
        fun rms(a: FloatArray, from: Int, to: Int): Double {
            var s = 0.0
            for (i in from until to) s += (a[i] * a[i]).toDouble()
            return sqrt(s / (to - from))
        }
        for (v in y) assertFalse(v.isNaN() || v.isInfinite())
        val attenuationDb = 20.0 * log10(rms(y, sr * 3, n - 1024) / rms(x, sr * 3, n - 1024))
        assertTrue("noise should drop by at least 3 dB, got $attenuationDb", attenuationDb < -3.0)
    }
}
