package com.chastechgroup.camix.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.audio.AudioSink
import com.chastechgroup.camix.camera.MediaStoreSaver
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records exactly what the GPU pipeline renders (look + Pulse FX baked in) together with the
 * DSP-processed microphone stream:
 *
 *   GL render thread ──► encoder input Surface ──► H.264 ─┐
 *   AudioProcessor ──► [AudioSink] ──► AAC-LC ────────────┼─► MediaMuxer ─► MediaStore (Movies/CamixUltra)
 *
 * Time base: everything is stamped on the System.nanoTime() (monotonic) clock relative to [start].
 * Camera frame timestamps (CLOCK_BOOTTIME on virtually all devices) are mapped onto it; if a device reports
 * something implausible the frame falls back to its arrival time, so A/V stay in sync either way.
 *
 * Lifecycle: prepare() -> (pipeline.startEncoder) -> start() -> ... -> stop(callback)
 */
class VideoRecorder(private val context: Context) : AudioSink {

    private class AudioChunk(val pcm: ShortArray, val count: Int, val ptsUs: Long)
    private class PendingSample(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    // output
    private var target: MediaStoreSaver.VideoTarget? = null
    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioCodec: MediaCodec? = null

    private var withAudio = false
    private var timelapse = false
    var width = 0; private set
    var height = 0; private set

    // timing
    @Volatile private var startNanos = 0L
    @Volatile private var running = false
    @Volatile private var audioActive = false
    @Volatile private var stopRequested = false
    private var realtimeOffsetNs = 0L
    private var lastVideoPtsNs = -1L
    private var audioBasePtsUs = -1L
    private var audioSamples = 0L

    // threads / queues
    private val audioQueue = LinkedBlockingQueue<AudioChunk>(128)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // muxer coordination
    private val muxLock = Any()
    private var expectedTracks = 1
    private var tracksAdded = 0
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = ArrayList<PendingSample>()
    @Volatile private var videoFrames = 0
    @Volatile private var failure: String? = null

    /**
     * Creates the output file and both encoders.
     * @return the Surface the GL pipeline must render into
     * @throws Exception when the device cannot encode the requested size (caller reports the error)
     */
    fun prepare(requestW: Int, requestH: Int, recordAudio: Boolean, timelapseMode: Boolean): Surface {
        withAudio = recordAudio
        timelapse = timelapseMode
        expectedTracks = if (recordAudio) 2 else 1

        target = MediaStoreSaver.newVideoTarget(context,
            (if (timelapseMode) "CamixUltra_TL_" else "CamixUltra_") + System.currentTimeMillis())
        val tgt = target!!
        muxer = if (tgt.pfd != null) {
            MediaMuxer(tgt.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(tgt.file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        // video encoder: try the requested size, then smaller fall-backs
        var surface: Surface? = null
        var lastError: Throwable? = null
        val sizes = arrayOf(
            intArrayOf(requestW, requestH),
            fit(requestW, requestH, 720),
            fit(requestW, requestH, 540)
        )
        for (s in sizes) {
            val w = s[0] and 1.inv()
            val h = s[1] and 1.inv()
            if (w < 160 || h < 160) continue
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(w, h, timelapseMode))
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    codec.start()
                } catch (t: Throwable) {
                    try { codec.release() } catch (_: Throwable) { }
                    throw t
                }
                videoCodec = codec
                width = w
                height = h
                break
            } catch (t: Throwable) {
                lastError = t
                Timber.w(t, "Video encoder %dx%d rejected", w, h)
            }
        }
        val input = surface
        if (input == null || videoCodec == null) {
            abortInternal()
            throw RuntimeException("No H.264 encoder available: " + (lastError?.message ?: "unknown"))
        }
        inputSurface = input

        if (recordAudio) {
            try {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    AudioProcessor.SAMPLE_RATE, 1)
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                audioCodec = codec
            } catch (t: Throwable) {
                Timber.w(t, "AAC encoder unavailable - recording video only")
                withAudio = false
                expectedTracks = 1
            }
        }
        return input
    }

    /** Begin accepting frames / audio. Call right after the pipeline has started feeding [prepare]'s surface. */
    fun start() {
        realtimeOffsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        startNanos = System.nanoTime()
        running = true
        audioActive = withAudio

        videoThread = Thread({ videoLoop() }, "camix-venc").also { it.start() }
        if (withAudio) audioThread = Thread({ audioLoop() }, "camix-aenc").also { it.start() }
    }

    val isWithAudio: Boolean get() = withAudio

    // ── Timestamps ────────────────────────────────────────────────────────────

    /** Encoder PTS (ns since start) for a camera frame; < 0 means "drop this frame". */
    fun videoPtsNs(cameraTimestampNs: Long): Long {
        val start = startNanos
        if (!running || start == 0L) return -1L
        val now = System.nanoTime()
        val mapped = cameraTimestampNs - realtimeOffsetNs
        val age = now - mapped
        val t = if (age in 0..500_000_000L) mapped else now - 30_000_000L
        var rel = t - start
        if (rel < 0L) return -1L
        if (rel <= lastVideoPtsNs) rel = lastVideoPtsNs + 1_000_000L
        lastVideoPtsNs = rel
        return rel
    }

    override fun onAudio(pcm: ShortArray, count: Int, endNanos: Long) {
        if (!audioActive || !running || count <= 0) return
        val start = startNanos
        if (endNanos < start) return
        if (audioBasePtsUs < 0L) {
            val chunkStartNs = endNanos - count * 1_000_000_000L / AudioProcessor.SAMPLE_RATE
            audioBasePtsUs = if (chunkStartNs > start) (chunkStartNs - start) / 1000L else 0L
            audioSamples = 0L
        }
        val ptsUs = audioBasePtsUs + audioSamples * 1_000_000L / AudioProcessor.SAMPLE_RATE
        audioSamples += count
        val chunk = AudioChunk(pcm.copyOf(count), count, ptsUs)
        if (!audioQueue.offer(chunk)) {
            audioQueue.poll()              // encoder is behind: drop the oldest chunk, keep the newest
            audioQueue.offer(chunk)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    /**
     * Finish the file asynchronously. The pipeline must already have stopped drawing into the surface.
     * [onDone] is called on a background thread with the saved Uri or an error message.
     */
    fun stop(onDone: (Uri?, String?) -> Unit) {
        Thread({
            audioActive = false
            stopRequested = true
            try { videoCodec?.signalEndOfInputStream() } catch (t: Throwable) { Timber.w(t, "signalEndOfInputStream") }
            try { videoThread?.join(6000) } catch (_: InterruptedException) { }
            try { audioThread?.join(6000) } catch (_: InterruptedException) { }
            running = false

            var error: String? = failure
            synchronized(muxLock) {
                // audio never produced a format (no samples arrived): still publish the video
                if (!muxerStarted && videoTrack >= 0 && tracksAdded >= 1) {
                    expectedTracks = tracksAdded
                    startMuxerLocked()
                }
                val m = muxer
                if (m != null) {
                    if (muxerStarted) {
                        try { m.stop() } catch (t: Throwable) {
                            Timber.e(t, "muxer.stop")
                            if (error == null) error = "Could not finalise the video file"
                        }
                    }
                    try { m.release() } catch (_: Throwable) { }
                }
                muxer = null
            }
            releaseCodecs()

            val keep = error == null && videoFrames > 0 && muxerStarted
            if (!keep && error == null) error = "Nothing was recorded"
            val uri = target?.let { MediaStoreSaver.finishVideo(context, it, keep) }
            target = null
            Timber.d("Recording finished: frames=%d keep=%b uri=%s", videoFrames, keep, uri)
            onDone(if (keep) uri else null, if (keep) null else error)
        }, "camix-rec-stop").start()
    }

    /** Throw everything away (e.g. camera stopped unexpectedly). */
    fun abort() {
        running = false
        audioActive = false
        stopRequested = true
        abortInternal()
    }

    private fun abortInternal() {
        try { videoThread?.join(500) } catch (_: Throwable) { }
        try { audioThread?.join(500) } catch (_: Throwable) { }
        synchronized(muxLock) {
            try { muxer?.release() } catch (_: Throwable) { }
            muxer = null
        }
        releaseCodecs()
        target?.let { MediaStoreSaver.finishVideo(context, it, false) }
        target = null
    }

    private fun releaseCodecs() {
        try { videoCodec?.stop() } catch (_: Throwable) { }
        try { videoCodec?.release() } catch (_: Throwable) { }
        videoCodec = null
        try { inputSurface?.release() } catch (_: Throwable) { }
        inputSurface = null
        try { audioCodec?.stop() } catch (_: Throwable) { }
        try { audioCodec?.release() } catch (_: Throwable) { }
        audioCodec = null
    }

    // ── Encoder loops ─────────────────────────────────────────────────────────

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false
        var idleSinceStop = 0
        while (!eos) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                failure = failure ?: "Video encoder failed: " + t.message
                break
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (stopRequested && ++idleSinceStop > 400) break         // ~4 s without data after stop
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(true, codec.outputFormat)
            } else if (idx >= 0) {
                idleSinceStop = 0
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    writeSample(true, buf, info)
                    videoFrames++
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
            }
        }
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var idleSinceStop = 0
        var stopSeenNs = 0L
        while (!outputEos) {
            if (stopRequested) {
                if (stopSeenNs == 0L) stopSeenNs = System.nanoTime()
                else if (System.nanoTime() - stopSeenNs > 5_000_000_000L) break
            }
            if (!inputEos) {
                val chunk = try { audioQueue.poll(10, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                if (chunk != null) {
                    feedAudio(codec, chunk)
                } else if (stopRequested && audioQueue.isEmpty()) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        codec.queueInputBuffer(i, 0, 0, audioBasePtsUsOrZero(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    }
                }
            }
            while (true) {
                val idx = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (t: Throwable) {
                    failure = failure ?: "Audio encoder failed: " + t.message
                    outputEos = true
                    break
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputEos && ++idleSinceStop > 300) outputEos = true
                    break
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(false, codec.outputFormat)
                } else if (idx >= 0) {
                    idleSinceStop = 0
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        writeSample(false, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { outputEos = true; break }
                }
            }
        }
    }

    private fun audioBasePtsUsOrZero(): Long {
        val base = audioBasePtsUs
        return if (base < 0L) 0L else base + audioSamples * 1_000_000L / AudioProcessor.SAMPLE_RATE
    }

    private fun feedAudio(codec: MediaCodec, chunk: AudioChunk) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i < 0) {
            Timber.w("AAC encoder busy - dropped %d samples", chunk.count)
            return
        }
        val buf = codec.getInputBuffer(i) ?: return
        buf.clear()
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk.pcm, 0, chunk.count)
        codec.queueInputBuffer(i, 0, chunk.count * 2, chunk.ptsUs, 0)
    }

    // ── Muxer coordination ────────────────────────────────────────────────────

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxLock) {
            val m = muxer ?: return
            try {
                val idx = m.addTrack(format)
                if (isVideo) videoTrack = idx else audioTrack = idx
                tracksAdded++
                if (!muxerStarted && tracksAdded >= expectedTracks) startMuxerLocked()
            } catch (t: Throwable) {
                failure = failure ?: "Muxer rejected the stream: " + t.message
                Timber.e(t, "addTrack")
            }
        }
    }

    private fun startMuxerLocked() {
        val m = muxer ?: return
        try {
            m.start()
            muxerStarted = true
            val info = MediaCodec.BufferInfo()
            for (p in pending) {
                info.set(0, p.data.size, p.ptsUs, p.flags)
                m.writeSampleData(p.track, ByteBuffer.wrap(p.data), info)
            }
            pending.clear()
        } catch (t: Throwable) {
            failure = failure ?: "Could not start the muxer: " + t.message
            Timber.e(t, "startMuxer")
        }
    }

    private fun writeSample(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            val m = muxer ?: return
            val track = if (isVideo) videoTrack else audioTrack
            if (track < 0) return
            try {
                if (!muxerStarted) {
                    // the other track's format has not arrived yet: keep the encoded sample until it does
                    val bytes = ByteArray(info.size)
                    data.get(bytes)
                    pending.add(PendingSample(track, bytes, info.presentationTimeUs, info.flags))
                } else {
                    m.writeSampleData(track, data, info)
                }
            } catch (t: Throwable) {
                failure = failure ?: "Writing the video failed: " + t.message
                Timber.e(t, "writeSample")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Scale (w, h) so the SHORT edge becomes [shortTarget]. */
    private fun fit(w: Int, h: Int, shortTarget: Int): IntArray {
        val shortEdge = minOf(w, h)
        if (shortEdge <= shortTarget) return intArrayOf(w, h)
        val k = shortTarget.toFloat() / shortEdge
        return intArrayOf(Math.round(w * k), Math.round(h * k))
    }

    private fun bitRateFor(w: Int, h: Int, timelapse: Boolean): Int {
        val pixels = w * h
        val perPixel = if (timelapse) 3 else 5
        return (pixels * perPixel).coerceIn(2_000_000, 20_000_000)
    }
}
