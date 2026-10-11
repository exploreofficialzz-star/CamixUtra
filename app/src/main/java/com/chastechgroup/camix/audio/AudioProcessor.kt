package com.chastechgroup.camix.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Receives the processed (post-DSP) PCM that ends up in the recorded video. */
interface AudioSink {
    /** @param endNanos System.nanoTime() when the LAST sample of this chunk was captured. */
    fun onAudio(pcm: ShortArray, count: Int, endNanos: Long)
}

enum class AudioPreset(val label: String) {
    NATURAL("Natural"),   // gentle wind-rumble cut + limiter
    VOICE("Voice"),       // rumble cut + spectral noise suppression + presence EQ + limiter
    MUSIC("Music")        // only DC/rumble protection + limiter; keeps the music untouched
}

/**
 * The ONE owner of the microphone.
 *
 *  raw mic ──► BeatDetector ──► AudioFeatures ──► PulseEngine (audio-reactive visuals)
 *     │
 *     └─► DSP chain (preset) ──► meter / waveform
 *                           └──► AudioSink ──► AAC encoder ──► the MP4's audio track
 *
 * Previous versions ran this DSP on a *copy* used only for the on-screen meter while CameraX recorded the
 * un-processed microphone, so none of the processing ever reached a saved video. Now the recorder consumes
 * the processed stream directly, and only one AudioRecord exists (no concurrent-capture conflicts).
 */
class AudioProcessor(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val CHUNK = 1024
        private const val WAVEFORM_BINS = 128
    }

    // ── Observable state (UI) ─────────────────────────────────────────────────
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _peakLevel = MutableStateFlow(0f)
    val peakLevel: StateFlow<Float> = _peakLevel.asStateFlow()

    private val _noiseFloorDb = MutableStateFlow(-90f)
    val noiseFloorDb: StateFlow<Float> = _noiseFloorDb.asStateFlow()

    private val _waveform = MutableStateFlow(FloatArray(WAVEFORM_BINS))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    private val _bpm = MutableStateFlow(0f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    /** Increments on every kick – drives the beat dot in the UI. */
    private val _kickCount = MutableStateFlow(0)
    val kickCount: StateFlow<Int> = _kickCount.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ── Hooks ─────────────────────────────────────────────────────────────────
    @Volatile var sink: AudioSink? = null
    @Volatile var featureListener: ((AudioFeatures) -> Unit)? = null
    @Volatile var preset: AudioPreset = AudioPreset.NATURAL

    // ── Internals ─────────────────────────────────────────────────────────────
    private var thread: Thread? = null
    @Volatile private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** @return true when capture is running (or already was). */
    @SuppressLint("MissingPermission")   // permission is verified by hasPermission() right below
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Timber.w("AudioProcessor: RECORD_AUDIO not granted")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Timber.e("AudioProcessor: getMinBufferSize=%d", minBuf)
            return false
        }
        val bufBytes = max(minBuf * 2, CHUNK * 2 * 8)
        val rec: AudioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (t: Throwable) {
            Timber.e(t, "AudioProcessor: AudioRecord construction failed")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioProcessor: AudioRecord not initialised (mic busy?)")
            rec.release()
            return false
        }
        running = true
        _isRunning.value = true
        thread = Thread({ captureLoop(rec) }, "camix-audio").also { it.start() }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread
        thread = null
        try { t?.join(800) } catch (_: InterruptedException) { }
        _isRunning.value = false
    }

    fun release() = stop()

    // ── Capture thread ────────────────────────────────────────────────────────

    private fun captureLoop(rec: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) { }

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Timber.e(t, "AudioProcessor: startRecording failed")
            rec.release()
            running = false
            _isRunning.value = false
            return
        }

        val beat = BeatDetector(SAMPLE_RATE)
        val pcm = ShortArray(CHUNK)
        val raw = FloatArray(CHUNK)
        val proc = FloatArray(CHUNK)
        val out = ShortArray(CHUNK)
        val rmsRing = FloatArray(90)            // ~2 s of chunk RMS values for the noise floor
        var ringPos = 0
        var ringCount = 0
        var wfTick = 0
        var lastBpm = 0f

        var appliedPreset: AudioPreset? = null
        var hp: Biquad? = null
        var presence: Biquad? = null
        var denoiser: SpectralDenoiser? = null

        while (running) {
            val n = rec.read(pcm, 0, CHUNK)
            if (n < 0) {
                Timber.e("AudioProcessor: read error %d", n)
                break
            }
            if (n == 0) continue
            val endNs = System.nanoTime()

            for (i in 0 until n) {
                val f = pcm[i] / 32768f
                raw[i] = f
                proc[i] = f
            }

            // 1) analysis on the RAW signal (the DSP below would eat bass / stationary music)
            val onsets = beat.process(raw, n)
            if (onsets.kick > 0f) _kickCount.value = _kickCount.value + 1
            val features = AudioFeatures(
                rms = beat.rms, bass = beat.bass, mid = beat.mid, high = beat.high,
                kick = onsets.kick, hit = onsets.hit, bpm = beat.bpm, bpmConfidence = beat.bpmConfidence)
            featureListener?.invoke(features)
            if (abs(beat.bpm - lastBpm) > 0.5f) { lastBpm = beat.bpm; _bpm.value = beat.bpm }

            // 2) DSP chain (rebuilt on this thread whenever the preset changes)
            val wanted = preset
            if (wanted != appliedPreset) {
                appliedPreset = wanted
                when (wanted) {
                    AudioPreset.NATURAL -> { hp = Biquad.highpass(SAMPLE_RATE, 80.0);  presence = null; denoiser = null }
                    AudioPreset.VOICE   -> {
                        hp = Biquad.highpass(SAMPLE_RATE, 140.0)
                        presence = Biquad.peaking(SAMPLE_RATE, 3000.0, 3.0)
                        denoiser = SpectralDenoiser()
                    }
                    AudioPreset.MUSIC   -> { hp = Biquad.highpass(SAMPLE_RATE, 35.0);  presence = null; denoiser = null }
                }
            }
            hp?.process(proc, n)
            denoiser?.process(proc, n)
            presence?.process(proc, n)
            SoftLimiter.process(proc, n)

            // 3) meter + waveform of what is actually recorded
            var sumSq = 0.0
            var peak = 0f
            for (i in 0 until n) {
                val v = proc[i]
                sumSq += (v * v).toDouble()
                val a = abs(v)
                if (a > peak) peak = a
            }
            val rms = sqrt(sumSq / n).toFloat()
            _rmsLevel.value = min(1f, rms * 3f)
            _peakLevel.value = min(1f, peak)
            rmsRing[ringPos] = rms
            ringPos = (ringPos + 1) % rmsRing.size
            if (ringCount < rmsRing.size) ringCount++
            if (++wfTick % 2 == 0) {
                var minRms = Float.MAX_VALUE
                for (i in 0 until ringCount) if (rmsRing[i] < minRms) minRms = rmsRing[i]
                _noiseFloorDb.value = if (minRms > 1e-6f) 20f * log10(minRms) else -90f

                val wf = FloatArray(WAVEFORM_BINS)
                val per = max(1, n / WAVEFORM_BINS)
                for (b in 0 until WAVEFORM_BINS) {
                    var m = 0f
                    val s = b * per
                    for (i in s until min(n, s + per)) m = max(m, abs(proc[i]))
                    wf[b] = min(1f, m * 2f)
                }
                _waveform.value = wf
            }

            // 4) recorder tap
            val s = sink
            if (s != null) {
                for (i in 0 until n) {
                    val v = proc[i]
                    val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                    out[i] = (c * 32767f).toInt().toShort()
                }
                s.onAudio(out, n, endNs)
            }
        }

        try { rec.stop() } catch (_: Throwable) { }
        rec.release()
        running = false
        _isRunning.value = false
        _rmsLevel.value = 0f
        _peakLevel.value = 0f
    }
}
