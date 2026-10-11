package com.chastechgroup.camix.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 FFT (double precision). Not thread-safe: one instance per thread. */
class Fft(private val n: Int) {

    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        val levels = 31 - Integer.numberOfLeadingZeros(n)
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n)
            sinTable[i] = sin(2.0 * PI * i / n)
        }
        for (i in 0 until n) {
            var r = 0
            var x = i
            for (b in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    /** Forward DFT (e^-j), unnormalised. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tpre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }

    /** Inverse DFT including the 1/N scaling (conjugation trick). */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) im[i] = -im[i]
        forward(re, im)
        val s = 1.0 / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] = -im[i] * s
        }
    }
}
