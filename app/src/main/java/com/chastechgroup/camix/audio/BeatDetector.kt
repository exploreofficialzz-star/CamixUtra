package com.chastechgroup.camix.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real-time onset + tempo detector (spectral flux), no external libraries.
 *
 *  - 1024-point Hann FFT every 512 samples (86 frames / s at 44.1 kHz)
 *  - log-compressed spectral flux in three bands (bass 30-150 Hz, mid 150-1000 Hz, high 1-8 kHz)
 *  - two adaptive-threshold peak pickers: KICK (bass flux) and HIT (broad-band flux)
 *  - a level gate (-52 dBFS) so room noise never triggers anything
 *  - tempo from the autocorrelation of the onset envelope with a log-normal prior around 118 BPM
 *
 * Reference implementation + measurements: proto/beat_final.py (kick P/R = 1.00 from 90-160 BPM on synthetic
 * drum loops, 0 false onsets on white noise at three levels, tempo within 1.5 % for 75-140 BPM).
 */
class BeatDetector(private val sampleRate: Int = 44100) {

    class Onsets(val kick: Float, val hit: Float)

    companion object {
        const val N = 1024
        const val HOP = 512
        const val BINS = N / 2 + 1
        private val NO_ONSETS = Onsets(0f, 0f)
    }

    private val fps = sampleRate.toDouble() / HOP
    private val fft = Fft(N)
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / N) }
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val prevLog = DoubleArray(BINS)

    private val fifo = DoubleArray(N + 16384)
    private var fifoLen = 0

    private val bassLo: Int
    private val bassHi: Int
    private val midHi: Int
    private val highHi: Int

    private val kickPeak = AdaptivePeak(43, 1.5, 0.35, 0.18, fps)
    private val hitPeak = AdaptivePeak(43, 1.8, 0.45, 0.10, fps)
    private val gate = 10.0.pow(-52.0 / 20.0)

    // onset envelope (circular) for tempo estimation
    private val env = DoubleArray(512)
    private var envHead = 0
    private var envCount = 0
    private var sinceTempo = 0
    private val tmp = DoubleArray(512)

    // outputs
    var bpm = 0f; private set
    var bpmConfidence = 0f; private set
    var bass = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var rms = 0f; private set

    private var candBpm = 0.0
    private var candCount = 0
    private var peakBass = 1e-3
    private var peakMid = 1e-3
    private var peakHigh = 1e-3

    init {
        val hz = sampleRate.toDouble() / N
        bassLo = max(1, Math.ceil(30.0 / hz).toInt())
        bassHi = (150.0 / hz).toInt()
        midHi = (1000.0 / hz).toInt()
        highHi = min(BINS - 1, (8000.0 / hz).toInt())
    }

    /**
     * Feed raw (pre-DSP) mono samples in [-1, 1]. Returns the strongest kick / hit detected inside this
     * call (strength 0 = none). Cheap enough for the audio thread (~0.1 ms per 1024 samples).
     */
    fun process(samples: FloatArray, count: Int): Onsets {
        for (i in 0 until count) fifo[fifoLen + i] = samples[i].toDouble()
        fifoLen += count

        var kick = 0.0
        var hit = 0.0
        var pos = 0
        while (fifoLen - pos >= N) {
            analyze(pos)
            if (lastKick > kick) kick = lastKick
            if (lastHit > hit) hit = lastHit
            pos += HOP
        }
        if (pos > 0) {
            System.arraycopy(fifo, pos, fifo, 0, fifoLen - pos)
            fifoLen -= pos
        }
        return if (kick == 0.0 && hit == 0.0) NO_ONSETS else Onsets(kick.toFloat(), hit.toFloat())
    }

    private var lastKick = 0.0
    private var lastHit = 0.0

    private fun analyze(off: Int) {
        var sum = 0.0
        for (i in 0 until N) {
            val x = fifo[off + i]
            sum += x * x
            re[i] = x * window[i]
            im[i] = 0.0
        }
        val frameRms = sqrt(sum / N)
        rms = frameRms.toFloat()
        fft.forward(re, im)

        var fb = 0.0; var fm = 0.0; var fh = 0.0
        var eb = 0.0; var em = 0.0; var eh = 0.0
        for (k in 1..highHi) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / (N / 4.0)
            val lg = ln(1.0 + 20.0 * mag)
            var d = lg - prevLog[k]
            if (d < 0.0) d = 0.0
            prevLog[k] = lg
            if (k < bassLo) continue
            if (k <= bassHi) { fb += d; eb += mag }
            else if (k <= midHi) { fm += d; em += mag }
            else { fh += d; eh += mag }
        }

        peakBass = max(max(peakBass * 0.9985, eb), 1e-3)
        peakMid = max(max(peakMid * 0.9985, em), 1e-3)
        peakHigh = max(max(peakHigh * 0.9985, eh), 1e-3)
        bass = min(1.0, eb / peakBass).toFloat()
        mid = min(1.0, em / peakMid).toFloat()
        high = min(1.0, eh / peakHigh).toFloat()

        var fAll = 1.6 * fb + fm + 0.6 * fh
        if (frameRms < gate) { fb = 0.0; fAll = 0.0 }

        val sk = kickPeak.push(1.6 * fb)
        val sh = hitPeak.push(fAll)
        lastKick = sk
        lastHit = if (sk > 0.0) 0.0 else sh

        env[envHead] = fAll
        envHead = (envHead + 1) % env.size
        if (envCount < env.size) envCount++
        sinceTempo++
        if (sinceTempo >= 43 && envCount >= 256) {
            sinceTempo = 0
            estimateTempo()
        }
    }

    private fun estimateTempo() {
        val n = envCount
        // chronological copy: oldest sample is at envHead when the buffer is full, else at 0
        val start = if (envCount == env.size) envHead else 0
        var mean = 0.0
        for (i in 0 until n) {
            val v = env[(start + i) % env.size]
            tmp[i] = v
            mean += v
        }
        mean /= n
        var e0 = 1e-9
        for (i in 0 until n) {
            tmp[i] -= mean
            e0 += tmp[i] * tmp[i]
        }

        val lagMin = (60.0 / 190.0 * fps).roundToInt()
        val lagMax = (60.0 / 65.0 * fps).roundToInt()
        var best = 0.0
        var bestLag = 0
        for (lag in lagMin..lagMax) {
            var r = 0.0
            for (i in 0 until n - lag) r += tmp[i] * tmp[i + lag]
            r /= e0
            val b = 60.0 * fps / lag
            val z = ln(b / 118.0) / ln(2.0) / 0.7
            val prior = exp(-0.5 * z * z)
            if (r * prior > best) {
                best = r * prior
                bestLag = lag
            }
        }
        if (bestLag == 0) return

        val est = 60.0 * fps / bestLag
        val cur = bpm.toDouble()
        if (cur == 0.0) {
            bpm = est.toFloat()
        } else if (abs(est - cur) / cur < 0.04) {
            bpm = (cur + (est - cur) * 0.35).toFloat()
            candCount = 0
        } else {
            if (abs(est - candBpm) / max(candBpm, 1.0) < 0.04) candCount++ else { candBpm = est; candCount = 1 }
            if (candCount >= 3) { bpm = est.toFloat(); candCount = 0 }
        }
        bpmConfidence = min(1.0, best * 2.0).toFloat()
    }

    /** Causal peak picker with one-frame delay and an adaptive threshold (mean + k*std over ~0.5 s). */
    private class AdaptivePeak(
        histLen: Int, private val kStd: Double, private val floor: Double,
        refractorySec: Double, fps: Double
    ) {
        private val hist = DoubleArray(histLen)
        private var count = 0
        private var head = 0
        private val refractory = (refractorySec * fps).toInt()
        private var v1 = 0.0
        private var v2 = 0.0
        private var last = -10_000
        private var t = 0

        fun push(v: Double): Double {
            var res = 0.0
            if (count >= 8) {
                var sum = 0.0
                for (i in 0 until count) sum += hist[i]
                val mean = sum / count
                var vs = 0.0
                for (i in 0 until count) { val d = hist[i] - mean; vs += d * d }
                val sd = sqrt(vs / count)
                val thr = mean + kStd * sd + floor
                if (v1 > thr && v1 >= v2 && v1 >= v && t - last > refractory) {
                    res = min(1.0, 0.4 + 1.2 * (v1 - thr) / (v1 + 1e-6))
                    last = t - 1
                }
            }
            hist[head] = v
            head = (head + 1) % hist.size
            if (count < hist.size) count++
            v2 = v1
            v1 = v
            t++
            return res
        }
    }
}
