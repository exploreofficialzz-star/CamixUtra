package com.chastechgroup.camix.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.chastechgroup.camix.filter.CameraFilterShaders
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.ShaderProgram
import com.chastechgroup.camix.pulse.PulseParams
import com.chastechgroup.camix.pulse.PulseSource
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The heart of CamixUltra's live rendering.
 *
 *   CameraX Preview ──► SurfaceTexture (external OES)
 *        │
 *   [A] copy / rotate / crop ──► upright base frame (FBO)
 *   [B] look: MASTER + STYLE  ──► look frame (FBO)           (same shaders as photos)
 *   [C] PULSE composite       ──► viewfinder  (SurfaceView, centre-crop, selfie mirror)
 *                             └─► video encoder surface (MediaCodec) – identical pixels
 *
 * One dedicated GL thread owns the EGL context; every public method is safe to call from any thread.
 */
class CameraGlPipeline(private val listener: Listener) {

    interface Listener {
        /** The GPU path cannot work on this device / session – caller should fall back to a plain preview. */
        fun onGlFatal(message: String)
        fun onFirstFrame()
    }

    /** Supplies the encoder timestamp (ns, relative to recording start) for a camera frame; < 0 = skip frame. */
    fun interface PtsProvider {
        fun ptsNs(cameraTimestampNs: Long): Long
    }

    // ── Threading ─────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("camix-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    // ── Cross-thread configuration ────────────────────────────────────────────
    @Volatile private var lookTarget: FilterParameters = FilterParameters.DEFAULT.copy(isAutoEnabled = false)
    @Volatile var pulseSource: PulseSource? = null
    @Volatile var contentWidth: Int = 0
        private set
    @Volatile var contentHeight: Int = 0
        private set
    /** centre-crop scale of the viewfinder (for tap-to-focus mapping) */
    @Volatile var cropScaleX: Float = 1f
        private set
    @Volatile var cropScaleY: Float = 1f
        private set
    @Volatile var mirrorDisplay: Boolean = false
        private set
    @Volatile private var released = false

    // ── GL-thread state ───────────────────────────────────────────────────────
    private class CameraInput(
        val texId: Int, val texture: SurfaceTexture, val surface: Surface,
        val bufW: Int, val bufH: Int
    ) { var lastTs: Long = Long.MIN_VALUE }

    private class WindowTarget(val surface: EGLSurface, val width: Int, val height: Int)

    private class EncoderTarget(
        val surface: EGLSurface, val width: Int, val height: Int,
        val manualPts: Boolean, val pts: PtsProvider
    )

    private var egl: EglCore? = null
    private var glReady = false
    private var failed = false
    private var quad: Quad? = null
    private var copyProgram: ShaderProgram? = null
    private val look = LookPass()
    private val pulsePass = PulsePass()
    private var baseFbo: Fbo? = null
    private var fboA: Fbo? = null
    private var fboB: Fbo? = null
    private val inputs = ArrayList<CameraInput>()
    private var input: CameraInput? = null
    private var rotationDeg = 90
    private var display: WindowTarget? = null
    private var encoder: EncoderTarget? = null
    private var currentLook: FilterParameters = lookTarget
    private var lastFrameNs = 0L
    private val startNs = System.nanoTime()
    private var firstFrameSent = false
    private var errorStreak = 0
    private var lastLookTex = 0
    private var lastPulse: PulseParams = PulseParams.NONE
    private val stMatrix = FloatArray(16)
    private val identity = GlUtil.identity()

    // ══════════════════════════════════════════════════════════════════════════
    // Public API (any thread)
    // ══════════════════════════════════════════════════════════════════════════

    /** Look to render. Changes fade in over ~0.35 s. */
    fun setLook(p: FilterParameters) { lookTarget = p }

    /** Viewfinder surface from the SurfaceView. Pass null (blocking) when the surface is destroyed. */
    fun setDisplay(surface: Surface?, width: Int, height: Int) {
        if (released) return
        if (surface == null) {
            callOnGl(800L) { dropDisplay() }
            return
        }
        handler.post {
            try {
                if (!ensureGl()) return@post
                val e = egl ?: return@post
                dropDisplay()
                val s = e.createWindowSurface(surface)
                val w = e.querySurface(s, EGL14.EGL_WIDTH)
                val h = e.querySurface(s, EGL14.EGL_HEIGHT)
                display = WindowTarget(s, if (w > 0) w else width, if (h > 0) h else height)
                Timber.d("Display surface attached %dx%d", width, height)
            } catch (t: Throwable) {
                fatal("Viewfinder surface failed: " + t.message, t)
            }
        }
    }

    /**
     * Called by the CameraX SurfaceProvider. [onReady] is invoked on the GL thread with the Surface
     * the camera should render into (or null when the GPU path is unavailable).
     */
    fun createCameraSurface(width: Int, height: Int, onReady: (Surface?) -> Unit) {
        if (released) { onReady(null); return }
        handler.post {
            if (!ensureGl()) { onReady(null); return@post }
            try {
                bindContext()
                val tex = GlUtil.createOesTexture()
                val st = SurfaceTexture(tex)
                st.setDefaultBufferSize(width, height)
                val surf = Surface(st)
                val inp = CameraInput(tex, st, surf, width, height)
                st.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener { drawFrame(inp) }, handler)
                inputs.add(inp)
                input = inp
                onReady(surf)
            } catch (t: Throwable) {
                fatal("Camera surface failed: " + t.message, t)
                onReady(null)
            }
        }
    }

    /** CameraX is done with [surface]; free its texture. */
    fun releaseCameraSurface(surface: Surface) {
        handler.post {
            val inp = inputs.firstOrNull { it.surface === surface } ?: return@post
            inputs.remove(inp)
            if (input === inp) input = null
            try {
                bindContext()
                inp.texture.setOnFrameAvailableListener(null)
                inp.surface.release()
                inp.texture.release()
                GlUtil.deleteTexture(inp.texId)
            } catch (t: Throwable) {
                Timber.w(t, "releaseCameraSurface")
            }
        }
    }

    /** Rotation CameraX asks for (clockwise degrees) and whether the viewfinder is mirrored (selfie). */
    fun updateTransform(rotationDegrees: Int, mirror: Boolean) {
        handler.post {
            rotationDeg = ((rotationDegrees % 360) + 360) % 360
            mirrorDisplay = mirror
        }
    }

    /**
     * Start feeding an encoder input surface. Blocking (<= 800 ms).
     * @param manualPts true for time-lapse: frames are pushed with [pushTimelapseFrame] instead of per camera frame.
     */
    fun startEncoder(surface: Surface, width: Int, height: Int, manualPts: Boolean, pts: PtsProvider): Boolean {
        val ok = callOnGl(800L) {
            val e = egl
            if (e == null || !glReady) false
            else {
                dropEncoder()
                val s = e.createWindowSurface(surface)
                encoder = EncoderTarget(s, width, height, manualPts, pts)
                true
            }
        }
        return ok == true
    }

    /** Stop feeding the encoder. Blocking (<= 800 ms). After this returns no more frames are drawn into it. */
    fun stopEncoder() {
        callOnGl(800L) { dropEncoder() }
    }

    /** Time-lapse: render the most recent look frame into the encoder with an explicit timestamp. */
    fun pushTimelapseFrame(ptsNs: Long) {
        handler.post {
            val e = egl ?: return@post
            val enc = encoder ?: return@post
            if (!enc.manualPts || lastLookTex == 0) return@post
            try {
                drawToEncoder(e, enc, lastLookTex, PulseParams.NONE, secondsNow(), ptsNs)
            } catch (t: Throwable) {
                Timber.w(t, "timelapse frame")
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            try { teardown() } catch (t: Throwable) { Timber.w(t, "teardown") }
            thread.quitSafely()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GL thread
    // ══════════════════════════════════════════════════════════════════════════

    private fun ensureGl(): Boolean {
        if (glReady) return true
        if (failed || released) return false
        try {
            val core = EglCore(recordable = true)
            egl = core
            if (!core.makeIdleCurrent()) throw RuntimeException("EGL context cannot be made current")
            quad = Quad()
            copyProgram = ShaderProgram(CameraFilterShaders.VERTEX_SHADER_DEFAULT,
                CameraFilterShaders.FRAGMENT_SHADER_OES_COPY)
            GlUtil.checkGlError("ensureGl")
            glReady = true
            return true
        } catch (t: Throwable) {
            fatal("GPU pipeline init failed: " + t.message, t)
            return false
        }
    }

    /** Make the context current on whatever surface exists (needed for texture create / delete). */
    private fun bindContext() {
        val e = egl ?: return
        val d = display
        if (d != null) { if (e.makeCurrent(d.surface)) return }
        e.makeIdleCurrent()
    }

    private fun dropDisplay() {
        val d = display ?: return
        val e = egl
        display = null
        if (e != null) {
            e.makeIdleCurrent()
            e.destroySurface(d.surface)
        }
    }

    private fun dropEncoder() {
        val enc = encoder ?: return
        val e = egl
        encoder = null
        if (e != null) {
            e.makeIdleCurrent()
            e.destroySurface(enc.surface)
        }
    }

    private fun secondsNow(): Float = (System.nanoTime() - startNs) / 1_000_000_000f

    private fun drawFrame(inp: CameraInput) {
        val e = egl ?: return
        if (!glReady || released || inp !== input) return
        try {
            val disp = display
            val ok = if (disp != null) e.makeCurrent(disp.surface) else e.makeIdleCurrent()
            if (!ok) {
                if (disp != null) dropDisplay()
                return
            }

            inp.texture.updateTexImage()
            val camTs = inp.texture.timestamp
            if (camTs == inp.lastTs) return            // no new image since the last draw
            inp.lastTs = camTs
            inp.texture.getTransformMatrix(stMatrix)

            val rot = rotationDeg
            val up = GlMath.uprightSize(inp.bufW, inp.bufH, rot)
            val size = contentSize(up[0], up[1])
            ensureTargets(size[0], size[1])
            val base = baseFbo ?: return
            val fa = fboA ?: return
            val fb = fboB ?: return
            val q = quad ?: return
            val copy = copyProgram ?: return

            val now = System.nanoTime()
            val dt = min(0.25f, max(0.001f, (now - lastFrameNs) / 1_000_000_000f))
            lastFrameNs = now
            val t = secondsNow()

            // [A] camera (external) -> upright base frame
            base.bind()
            q.draw(copy, GlMath.cameraToUpright(inp.bufW, inp.bufH, rot), stMatrix,
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inp.texId)

            // [B] look
            currentLook = currentLook.blendTo(lookTarget, 1f - exp(-dt / 0.35f))
            val lookTex = look.render(base.texture, fa, fb, currentLook,
                size[0].toFloat(), size[1].toFloat(), t, q)
            lastLookTex = lookTex

            // [C] pulse composite -> viewfinder, encoder
            val pp = pulseSource?.step(dt) ?: PulseParams.NONE
            lastPulse = pp

            if (disp != null) {
                val crop = GlMath.cropScale(size[0], size[1], disp.width, disp.height)
                cropScaleX = crop[0]; cropScaleY = crop[1]
                val mx = if (mirrorDisplay) -crop[0] else crop[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, disp.width, disp.height)
                if (!pulsePass.draw(lookTex, pp, GlMath.scaleMatrix(mx, crop[1]), q, t)) {
                    fatal("Pulse shader unavailable", null)
                    return
                }
                e.swapBuffers(disp.surface)
            }

            val enc = encoder
            if (enc != null && !enc.manualPts) {
                val pts = enc.pts.ptsNs(camTs)
                if (pts >= 0L) drawToEncoder(e, enc, lookTex, pp, t, pts)
            }

            errorStreak = 0
            if (!firstFrameSent) {
                firstFrameSent = true
                mainHandler.post { listener.onFirstFrame() }
            }
        } catch (t: Throwable) {
            errorStreak++
            Timber.e(t, "drawFrame failed (%d)", errorStreak)
            if (errorStreak >= 5) fatal("Rendering failed repeatedly: " + t.message, t)
        }
    }

    private fun drawToEncoder(e: EglCore, enc: EncoderTarget, lookTex: Int, pp: PulseParams, t: Float, ptsNs: Long) {
        val q = quad ?: return
        if (!e.makeCurrent(enc.surface)) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, enc.width, enc.height)
        pulsePass.draw(lookTex, pp, identity, q, t)
        e.setPresentationTime(enc.surface, ptsNs)
        e.swapBuffers(enc.surface)
    }

    /** Upright frame size capped to 1080x1920 (even numbers). */
    private fun contentSize(w: Int, h: Int): IntArray {
        val longEdge = max(w, h).toFloat()
        val s = min(1f, 1920f / longEdge)
        var cw = (w * s).roundToInt()
        var ch = (h * s).roundToInt()
        if (cw % 2 != 0) cw += 1
        if (ch % 2 != 0) ch += 1
        return intArrayOf(cw, ch)
    }

    private fun ensureTargets(w: Int, h: Int) {
        val b = baseFbo
        if (b != null && b.width == w && b.height == h) return
        baseFbo?.release(); fboA?.release(); fboB?.release()
        baseFbo = Fbo(w, h)
        fboA = Fbo(w, h)
        fboB = Fbo(w, h)
        contentWidth = w
        contentHeight = h
        lastLookTex = 0
        Timber.d("Render targets %dx%d", w, h)
    }

    private fun fatal(msg: String, t: Throwable?) {
        if (failed) return
        failed = true
        Timber.e(t, "GL FATAL: %s", msg)
        mainHandler.post { listener.onGlFatal(msg) }
    }

    private fun teardown() {
        val e = egl
        if (e != null) {
            try { e.makeIdleCurrent() } catch (_: Throwable) { }
            dropEncoder()
            dropDisplay()
            for (inp in inputs) {
                try {
                    inp.texture.setOnFrameAvailableListener(null)
                    inp.surface.release()
                    inp.texture.release()
                    GlUtil.deleteTexture(inp.texId)
                } catch (_: Throwable) { }
            }
            inputs.clear()
            input = null
            try {
                baseFbo?.release(); fboA?.release(); fboB?.release()
                look.release(); pulsePass.release(); copyProgram?.release()
            } catch (_: Throwable) { }
            baseFbo = null; fboA = null; fboB = null; copyProgram = null
            e.release()
        }
        egl = null
        glReady = false
    }

    /** Run [block] on the GL thread and wait (bounded). Runs inline when already on the GL thread. */
    private fun <T> callOnGl(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == thread.looper) return block()
        if (!thread.isAlive) return null
        val latch = CountDownLatch(1)
        var result: T? = null
        val posted = handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Timber.w(t, "callOnGl")
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }
}
