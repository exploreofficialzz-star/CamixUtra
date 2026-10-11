package com.chastechgroup.camix.gl

import android.opengl.GLES20
import com.chastechgroup.camix.filter.CameraFilterShaders
import com.chastechgroup.camix.filter.ShaderProgram
import com.chastechgroup.camix.pulse.PulseParams
import timber.log.Timber

/**
 * Final composite: samples the finished look and applies the audio-reactive effects.
 * With PulseParams.NONE it is a plain blit, so it is also what draws the viewfinder crop / mirror
 * and what feeds the video encoder.  GL thread only.
 */
class PulsePass {

    private var program: ShaderProgram? = null
    private var failed = false
    private val identity = GlUtil.identity()

    private fun prog(): ShaderProgram? {
        if (program == null && !failed) {
            try {
                program = ShaderProgram(CameraFilterShaders.VERTEX_SHADER_DEFAULT,
                    CameraFilterShaders.FRAGMENT_SHADER_PULSE)
            } catch (t: Throwable) {
                failed = true
                Timber.e(t, "Pulse shader failed to build")
            }
        }
        return program
    }

    /** @return false when the shader is unavailable (caller should treat that as a fatal GL error). */
    fun draw(texture: Int, p: PulseParams, mvp: FloatArray, quad: Quad, timeSec: Float): Boolean {
        val pr = prog() ?: return false
        pr.use()
        pr.setFloat("uZoom", if (p.zoom < 1f) 1f else p.zoom)
        pr.setVec2("uShake", p.shakeX, p.shakeY)
        pr.setFloat("uRgb", p.rgb)
        pr.setFloat("uRgbAngle", p.rgbAngle)
        pr.setFloat("uGlitch", p.glitch)
        pr.setFloat("uFlash", p.flash)
        pr.setFloat("uHue", p.hue)
        pr.setFloat("uSat", p.sat)
        pr.setFloat("uBass", p.bass)
        pr.setFloat("uScan", p.scan)
        pr.setFloat("uTime", timeSec)
        quad.draw(pr, mvp, identity, GLES20.GL_TEXTURE_2D, texture)
        return true
    }

    fun release() {
        program?.release()
        program = null
    }
}
