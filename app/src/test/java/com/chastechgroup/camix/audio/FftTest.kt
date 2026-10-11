package com.chastechgroup.camix.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FftTest {

    @Test
    fun forwardMatchesNaiveDft() {
        val n = 64
        val rnd = Random(7)
        val x = DoubleArray(n) { rnd.nextGaussian() }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                sr += x[t] * cos(a)
                si += x[t] * sin(a)
            }
            assertEquals("re[$k]", sr, re[k], 1e-9)
            assertEquals("im[$k]", si, im[k], 1e-9)
        }
    }

    @Test
    fun inverseRecoversInput() {
        val n = 512
        val rnd = Random(11)
        val x = DoubleArray(n) { rnd.nextGaussian() }
        val re = x.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) assertEquals(x[i], re[i], 1e-9)
    }
}
