package com.chastechgroup.camix.pulse

/** One frame worth of audio-reactive visual parameters (consumed by the PULSE shader pass). */
data class PulseParams(
    val zoom: Float = 1f,        // >= 1  zoom punch
    val shakeX: Float = 0f,      // uv offset
    val shakeY: Float = 0f,
    val rgb: Float = 0f,         // chromatic aberration distance (uv)
    val rgbAngle: Float = 0f,    // radians
    val glitch: Float = 0f,      // 0..1
    val flash: Float = 0f,       // 0..1 additive strobe
    val hue: Float = 0f,         // radians
    val sat: Float = 1f,         // saturation multiplier
    val bass: Float = 0f,        // 0..1
    val scan: Float = 0f         // 0..1 VHS
) {
    companion object {
        val NONE = PulseParams()
    }
}

/** Provides pulse parameters to the render thread. */
interface PulseSource {
    /** Advance the effect envelopes by [dtSec] and return the parameters for this frame. */
    fun step(dtSec: Float): PulseParams
}
