package com.chastechgroup.camix.gl

import android.opengl.GLES20
import com.chastechgroup.camix.filter.CameraFilterShaders
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.ShaderProgram
import com.chastechgroup.camix.filter.StyleMode
import timber.log.Timber

/**
 * Runs the "look": MASTER (colour / tone / detail) and then one optional STYLE shader, ping-ponging
 * between two framebuffers. Input and output are plain 2-D textures so the same class serves the
 * live viewfinder, the video encoder and full-resolution photos.
 *
 * GL thread only.
 */
class LookPass {

    private val programs = HashMap<String, ShaderProgram?>()
    private val identity = GlUtil.identity()

    private fun program(key: String, fragment: String): ShaderProgram? {
        if (programs.containsKey(key)) return programs[key]
        val p = try {
            ShaderProgram(CameraFilterShaders.VERTEX_SHADER_DEFAULT, fragment)
        } catch (t: Throwable) {
            Timber.e(t, "Look shader '%s' failed to build - stage will be skipped", key)
            null
        }
        programs[key] = p
        return p
    }

    private fun styleProgram(style: StyleMode): ShaderProgram? = when (style) {
        StyleMode.NONE       -> null
        StyleMode.CINEMATIC  -> program("cinematic",  CameraFilterShaders.FRAGMENT_SHADER_CINEMATIC)
        StyleMode.THERMAL    -> program("thermal",    CameraFilterShaders.FRAGMENT_SHADER_THERMAL)
        StyleMode.ULTRASOUND -> program("ultrasound", CameraFilterShaders.FRAGMENT_SHADER_ULTRASOUND)
        StyleMode.PEAKING    -> program("peaking",    CameraFilterShaders.FRAGMENT_SHADER_FOCUS_PEAKING)
        StyleMode.PORTRAIT   -> program("portrait",   CameraFilterShaders.FRAGMENT_SHADER_PORTRAIT)
        StyleMode.NIGHT      -> program("night",      CameraFilterShaders.FRAGMENT_SHADER_NIGHT)
        StyleMode.VIVID      -> program("vivid",      CameraFilterShaders.FRAGMENT_SHADER_VIVID)
        StyleMode.TOON       -> program("toon",       CameraFilterShaders.FRAGMENT_SHADER_TOON)
        StyleMode.NEON       -> program("neon",       CameraFilterShaders.FRAGMENT_SHADER_NEON)
        StyleMode.DREAM      -> program("dream",      CameraFilterShaders.FRAGMENT_SHADER_DREAM)
    }

    /**
     * @param source   2-D texture holding the upright frame
     * @param a,b      ping-pong targets (same size as the work area)
     * @param refW,refH  virtual pixel dimensions used by the shaders for neighbour offsets
     *                   (photos pass preview-equivalent values so the look matches the viewfinder)
     * @return texture holding the finished look (may be [source] when nothing needed to run)
     */
    fun render(
        source: Int, a: Fbo, b: Fbo, params: FilterParameters,
        refW: Float, refH: Float, timeSec: Float, quad: Quad
    ): Int {
        var cur = source

        // CINEMATIC has its own grain; avoid doubling it in the master stage
        val pm = if (params.style == StyleMode.CINEMATIC) params.copy(grain = 0f) else params

        if (!pm.masterIsNeutral()) {
            val prog = program("master", CameraFilterShaders.FRAGMENT_SHADER_MASTER)
            if (prog != null) {
                a.bind()
                prog.use()
                setMaster(prog, pm, refW, refH, timeSec)
                quad.draw(prog, identity, identity, GLES20.GL_TEXTURE_2D, cur)
                cur = a.texture
            }
        }

        if (params.style != StyleMode.NONE) {
            val prog = styleProgram(params.style)
            if (prog != null) {
                val dst = if (cur == a.texture) b else a
                dst.bind()
                prog.use()
                setStyle(prog, params, refW, refH, timeSec)
                quad.draw(prog, identity, identity, GLES20.GL_TEXTURE_2D, cur)
                cur = dst.texture
            }
        }
        return cur
    }

    private fun setMaster(p: ShaderProgram, f: FilterParameters, w: Float, h: Float, t: Float) {
        p.setFloat("uBrightness", f.brightness)
        p.setFloat("uContrast", f.contrast)
        p.setFloat("uSaturation", f.saturation)
        p.setFloat("uExposure", f.exposure)
        p.setFloat("uWarmth", f.warmth)
        p.setFloat("uTint", f.tint)
        p.setFloat("uHue", f.hue)
        p.setFloat("uHighlights", f.highlights)
        p.setFloat("uShadows", f.shadows)
        p.setFloat("uFade", f.fade)
        p.setFloat("uVignette", f.vignette)
        p.setFloat("uSharpen", f.sharpen)
        p.setFloat("uGrain", f.grain)
        p.setFloat("uClarityBoost", f.clarityBoost)
        p.setFloat("uDehaze", f.dehaze)
        p.setFloat("uImageWidth", w)
        p.setFloat("uImageHeight", h)
        p.setFloat("uTime", t)
    }

    private fun setStyle(p: ShaderProgram, f: FilterParameters, w: Float, h: Float, t: Float) {
        p.setFloat("uImageWidth", w)
        p.setFloat("uImageHeight", h)
        p.setFloat("uTime", t)
        when (f.style) {
            StyleMode.CINEMATIC -> {
                p.setFloat("uIntensity", f.cinematicIntensity)
                p.setFloat("uGrain", f.grain)
            }
            StyleMode.THERMAL -> {
                p.setFloat("uIntensity", f.thermalIntensity)
                p.setFloat("uContrast", f.thermalContrast)
            }
            StyleMode.ULTRASOUND -> {
                p.setFloat("uEdgeStrength", f.ultrasoundEdge)
                p.setFloat("uBrightness", f.ultrasoundBrightness)
            }
            StyleMode.PEAKING -> {
                p.setFloat("uThreshold", f.focusPeakingThreshold)
                p.setFloat("uPeakingColor", f.focusPeakingColor)
            }
            StyleMode.PORTRAIT -> {
                p.setFloat("uBlurAmount", f.styleIntensity)
                p.setFloat("uSkinSmooth", 0.6f)
            }
            StyleMode.NIGHT -> p.setFloat("uNoiseReduction", f.styleIntensity)
            StyleMode.VIVID, StyleMode.TOON, StyleMode.NEON, StyleMode.DREAM ->
                p.setFloat("uIntensity", f.styleIntensity)
            StyleMode.NONE -> Unit
        }
    }

    fun release() {
        for (p in programs.values) p?.release()
        programs.clear()
    }
}
