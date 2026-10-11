package com.chastechgroup.camix.pulse

import com.chastechgroup.camix.audio.AudioFeatures
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Which visual channels a Pulse style drives (weights 0..1).
 * Same vocabulary AI-music-video / short-form editors use: punch-in on the kick, RGB split, glitch,
 * strobe, hue spin and VHS tape.
 */
enum class PulseStyle(
    val label: String,
    val wZoom: Float, val wRgb: Float, val wFlash: Float, val wGlitch: Float,
    val wHue: Float, val wShake: Float, val wScan: Float, val wSat: Float
) {
    FUSION("Fusion",   0.80f, 0.60f, 0.30f, 0.25f, 0.50f, 0.30f, 0.0f, 0.6f),
    PUNCH("Punch",     1.00f, 0.15f, 0.00f, 0.00f, 0.00f, 0.35f, 0.0f, 0.3f),
    SPLIT("RGB Split", 0.30f, 1.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.0f, 0.3f),
    GLITCH("Glitch",   0.20f, 0.50f, 0.00f, 1.00f, 0.00f, 0.40f, 0.0f, 0.0f),
    STROBE("Strobe",   0.35f, 0.00f, 1.00f, 0.00f, 0.00f, 0.00f, 0.0f, 0.0f),
    SPIN("Hue Spin",   0.30f, 0.00f, 0.00f, 0.00f, 1.00f, 0.00f, 0.0f, 1.0f),
    VHS("VHS",         0.00f, 0.40f, 0.00f, 0.30f, 0.00f, 0.00f, 1.0f, 0.5f)
}

/**
 * Turns what the microphone hears into per-frame visual parameters.
 *  - [onFeatures] is called from the audio thread (every ~23 ms) and fires envelopes on onsets.
 *  - [step] is called from the render thread once per frame and returns decayed, scaled parameters.
 * Pure Kotlin (no Android types) so it is unit-testable on the JVM.
 */
class PulseEngine : PulseSource {

    @Volatile var enabled: Boolean = false
    @Volatile var style: PulseStyle = PulseStyle.FUSION
    /** 0 .. 1.5 (UI slider) */
    @Volatile var intensity: Float = 0.8f

    private val lock = Any()
    private var punch = 0f
    private var rgbEnv = 0f
    private var flashEnv = 0f
    private var glitchEnv = 0f
    private var shakeEnv = 0f
    private var hueTarget = 0f
    private var hue = 0f
    private var bass = 0f
    private var rgbAngle = 0f
    private var shakeDirX = 1f
    private var shakeDirY = 0f
    private var seed = 20240611

    fun onFeatures(f: AudioFeatures) {
        if (!enabled) return
        synchronized(lock) {
            bass += (f.bass * min(1f, f.rms * 12f) - bass) * 0.35f
            if (f.kick > 0f) trigger(f.kick) else if (f.hit > 0f) trigger(f.hit * 0.45f)
        }
    }

    private fun trigger(s: Float) {
        punch = max(punch, s)
        rgbEnv = max(rgbEnv, s)
        flashEnv = max(flashEnv, s * 0.8f)
        glitchEnv = max(glitchEnv, s * 0.9f)
        shakeEnv = max(shakeEnv, s)
        hueTarget += s * 0.9f
        rgbAngle += 1.7f
        shakeDirX = nextRand() * 2f - 1f
        shakeDirY = nextRand() * 2f - 1f
    }

    private fun nextRand(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    fun reset() {
        synchronized(lock) {
            punch = 0f; rgbEnv = 0f; flashEnv = 0f; glitchEnv = 0f; shakeEnv = 0f
            hueTarget = 0f; hue = 0f; bass = 0f
        }
    }

    override fun step(dtSec: Float): PulseParams {
        if (!enabled) return PulseParams.NONE
        return synchronized(lock) {
            punch *= exp(-dtSec / 0.20f)
            rgbEnv *= exp(-dtSec / 0.25f)
            flashEnv *= exp(-dtSec / 0.08f)
            glitchEnv *= exp(-dtSec / 0.12f)
            shakeEnv *= exp(-dtSec / 0.14f)
            bass *= exp(-dtSec / 0.5f)
            hue += (hueTarget - hue) * (1f - exp(-dtSec / 0.12f))
            if (hueTarget > 50f) { hueTarget -= 50f; hue -= 50f }   // keep floats small; the shader is periodic anyway

            val k = style
            val g = intensity
            PulseParams(
                zoom = 1f + 0.12f * punch * k.wZoom * g + 0.03f * shakeEnv * k.wShake * g,
                shakeX = shakeDirX * 0.012f * shakeEnv * k.wShake * g,
                shakeY = shakeDirY * 0.012f * shakeEnv * k.wShake * g,
                rgb = 0.014f * rgbEnv * k.wRgb * g,
                rgbAngle = rgbAngle,
                glitch = min(1f, glitchEnv * k.wGlitch * g),
                flash = 0.55f * flashEnv * k.wFlash * g,
                hue = hue * k.wHue,
                sat = 1f + 0.35f * bass * k.wSat * g,
                bass = min(1f, bass * g),
                scan = k.wScan * min(1f, g)
            )
        }
    }
}
