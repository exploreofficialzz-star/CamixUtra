package com.chastechgroup.camix.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import timber.log.Timber

/**
 * Minimal EGL 1.4 / OpenGL ES 2.0 helper: one context, any number of window / pbuffer surfaces.
 * Every method must be called from the SAME thread (the GL thread).
 *
 * @param recordable   ask for EGL_RECORDABLE_ANDROID so surfaces can feed a MediaCodec encoder
 * @param pbufferOnly  config is for off-screen work only (still-photo renderer)
 */
class EglCore(recordable: Boolean = true, private val pbufferOnly: Boolean = false) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var idleTried = false
    private var surfaceless = false

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("eglInitialize failed")
        }

        val cfg = (if (recordable) chooseConfig(true) else null) ?: chooseConfig(false)
            ?: throw RuntimeException("no suitable EGLConfig")
        config = cfg

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        checkEgl("eglCreateContext")
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext returned NO_CONTEXT")

        val ext = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        surfaceless = ext.contains("EGL_KHR_surfaceless_context")
        Timber.d("EglCore ready (EGL %d.%d, surfaceless=%b)", version[0], version[1], surfaceless)
    }

    private fun chooseConfig(withRecordable: Boolean): EGLConfig? {
        val a = ArrayList<Int>()
        a.add(EGL14.EGL_RED_SIZE);   a.add(8)
        a.add(EGL14.EGL_GREEN_SIZE); a.add(8)
        a.add(EGL14.EGL_BLUE_SIZE);  a.add(8)
        a.add(EGL14.EGL_ALPHA_SIZE); a.add(8)
        a.add(EGL14.EGL_RENDERABLE_TYPE); a.add(EGL14.EGL_OPENGL_ES2_BIT)
        if (pbufferOnly) { a.add(EGL14.EGL_SURFACE_TYPE); a.add(EGL14.EGL_PBUFFER_BIT) }
        if (withRecordable) { a.add(EGLExt.EGL_RECORDABLE_ANDROID); a.add(1) }
        a.add(EGL14.EGL_NONE)

        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, a.toIntArray(), 0, configs, 0, 1, num, 0)
        return if (ok && num[0] > 0) configs[0] else null
    }

    // ── Surfaces ──────────────────────────────────────────────────────────────

    fun createWindowSurface(surface: Surface): EGLSurface {
        val cfg = config ?: throw RuntimeException("EGL config missing")
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val s = EGL14.eglCreateWindowSurface(display, cfg, surface, attribs, 0)
        checkEgl("eglCreateWindowSurface")
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val cfg = config ?: throw RuntimeException("EGL config missing")
        val attribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(display, cfg, attribs, 0)
        checkEgl("eglCreatePbufferSurface")
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
        return s
    }

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun querySurface(surface: EGLSurface, what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(display, surface, what, v, 0)
        return v[0]
    }

    // ── Current-ness ──────────────────────────────────────────────────────────

    fun makeCurrent(surface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    /** Make the context current without a visible surface (1x1 pbuffer, or surfaceless as fallback). */
    fun makeIdleCurrent(): Boolean {
        if (idleSurface == EGL14.EGL_NO_SURFACE && !idleTried) {
            idleTried = true
            try {
                idleSurface = createPbufferSurface(1, 1)
            } catch (t: Throwable) {
                idleSurface = EGL14.EGL_NO_SURFACE
                Timber.w("Idle pbuffer not available: %s", t.message)
            }
        }
        if (idleSurface != EGL14.EGL_NO_SURFACE) return makeCurrent(idleSurface)
        if (surfaceless) return EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
        return false
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Timestamp (ns) the encoder will see for the frame that is about to be swapped. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idleSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // eglTerminate is deliberately NOT called: the display is process-wide and shared with
            // other EglCore instances (still-photo renderer).
        }
        idleSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
    }

    private fun checkEgl(op: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) throw RuntimeException("$op: EGL error 0x" + Integer.toHexString(err))
    }
}
