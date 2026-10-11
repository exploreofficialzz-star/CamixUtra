package com.chastechgroup.camix.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ-cookbook biquad, transposed direct form II. State persists across buffers (no clicks). */
class Biquad private constructor() {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }

    fun process(buf: FloatArray, n: Int) {
        var s1 = z1
        var s2 = z2
        for (i in 0 until n) {
            val x = buf[i].toDouble()
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            buf[i] = y.toFloat()
        }
        z1 = s1
        z2 = s2
    }

    companion object {
        fun highpass(sampleRate: Int, fc: Double, q: Double = 0.70710678): Biquad {
            val w0 = 2.0 * PI * fc / sampleRate
            val c = cos(w0)
            val al = sin(w0) / (2.0 * q)
            return Biquad().also { it.set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al) }
        }

        fun peaking(sampleRate: Int, fc: Double, gainDb: Double, q: Double = 0.9): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * fc / sampleRate
            val c = cos(w0)
            val al = sin(w0) / (2.0 * q)
            return Biquad().also { it.set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a) }
        }
    }
}

/** Smooth saturating limiter: transparent below 0.7, asymptotically approaches 1.0 (never clips). */
object SoftLimiter {
    fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = buf[i]
            val a = abs(x)
            if (a > 0.7f) {
                val y = 0.7f + 0.3f * tanh((a - 0.7f) / 0.3f)
                buf[i] = if (x < 0f) -y else y
            }
        }
    }
}

/**
 * Streaming spectral-subtraction noise suppressor ("Voice Clean").
 *  - 512-point sqrt-Hann STFT, 50 % overlap (perfect reconstruction), 5.8 ms latency
 *  - noise floor per bin by minimum statistics over ~1.5 s (learns while you talk – no calibration phase)
 *  - over-subtraction + gain floor + attack/release + 3-tap frequency smoothing (suppresses musical noise)
 *
 * Limitation (by design): stationary sounds – including a held musical note – are treated as noise,
 * so use it for speech, not for music.
 */
class SpectralDenoiser(
    private val floorGain: Double = 0.2,
    private val over: Double = 1.5,
    private val bias: Double = 1.8
) {
    companion object {
        const val N = 512
        const val HOP = 256
        const val BINS = 257
        const val SUB = 43
        const val NSUB = 6
        const val LATENCY = N - HOP
    }

    private val fft = Fft(N)
    private val win = DoubleArray(N) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / N)) }
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)

    private val inBuf = DoubleArray(N + 16384)
    private var inLen = 0
    private val outQ = DoubleArray(LATENCY + 16384 + HOP)
    private var outLen = LATENCY                      // pre-filled with silence => constant latency
    private val ola = DoubleArray(N)

    private val pw = DoubleArray(BINS)
    private val smoothed = DoubleArray(BINS)
    private var haveSmoothed = false
    private val subMin = DoubleArray(BINS) { 1e30 }
    private var subCount = 0
    private val ring = Array(NSUB) { DoubleArray(BINS) }
    private var ringPos = 0
    private var ringCount = 0
    private val noise = DoubleArray(BINS)
    private var haveNoise = false
    private val gPrev = DoubleArray(BINS) { 1.0 }
    private val gRaw = DoubleArray(BINS)

    /** Processes [n] samples in place (n <= 16384). Output is delayed by [LATENCY] samples. */
    fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) inBuf[inLen + i] = buf[i].toDouble()
        inLen += n

        var pos = 0
        while (inLen - pos >= N) {
            frame(pos)
            pos += HOP
        }
        if (pos > 0) {
            System.arraycopy(inBuf, pos, inBuf, 0, inLen - pos)
            inLen -= pos
        }

        val take = min(n, outLen)
        for (i in 0 until take) buf[i] = outQ[i].toFloat()
        for (i in take until n) buf[i] = 0f
        System.arraycopy(outQ, take, outQ, 0, outLen - take)
        outLen -= take
    }

    private fun frame(off: Int) {
        for (i in 0 until N) {
            re[i] = inBuf[off + i] * win[i]
            im[i] = 0.0
        }
        fft.forward(re, im)

        for (k in 0 until BINS) pw[k] = re[k] * re[k] + im[k] * im[k]
        if (!haveSmoothed) {
            for (k in 0 until BINS) smoothed[k] = pw[k]
            haveSmoothed = true
        } else {
            for (k in 0 until BINS) smoothed[k] = 0.85 * smoothed[k] + 0.15 * pw[k]
        }
        for (k in 0 until BINS) subMin[k] = min(subMin[k], smoothed[k])
        subCount++

        if (!haveNoise) {
            for (k in 0 until BINS) noise[k] = subMin[k] * bias
            haveNoise = true
        }
        if (subCount >= SUB) {
            System.arraycopy(subMin, 0, ring[ringPos], 0, BINS)
            ringPos = (ringPos + 1) % NSUB
            if (ringCount < NSUB) ringCount++
            for (k in 0 until BINS) {
                var m = ring[0][k]
                for (r in 1 until ringCount) m = min(m, ring[r][k])
                noise[k] = m * bias
            }
            for (k in 0 until BINS) subMin[k] = 1e30
            subCount = 0
        } else if (ringCount == 0) {
            for (k in 0 until BINS) noise[k] = min(noise[k], subMin[k] * bias)
        }

        val floor2 = floorGain * floorGain
        for (k in 0 until BINS) {
            val g2 = 1.0 - over * noise[k] / (pw[k] + 1e-12)
            var g = sqrt(max(g2, floor2))
            g = if (g > gPrev[k]) g else 0.55 * gPrev[k] + 0.45 * g
            gPrev[k] = g
            gRaw[k] = g
        }
        for (k in 0 until BINS) {
            val gs = if (k == 0 || k == BINS - 1) gRaw[k]
                     else 0.25 * gRaw[k - 1] + 0.5 * gRaw[k] + 0.25 * gRaw[k + 1]
            re[k] *= gs
            im[k] *= gs
        }
        for (k in 1 until N / 2) {
            re[N - k] = re[k]
            im[N - k] = -im[k]
        }
        fft.inverse(re, im)

        for (i in 0 until N) ola[i] += re[i] * win[i]
        for (i in 0 until HOP) outQ[outLen + i] = ola[i]
        outLen += HOP
        System.arraycopy(ola, HOP, ola, 0, N - HOP)
        for (i in N - HOP until N) ola[i] = 0.0
    }
}
