package com.chastechgroup.camix.audio

/** Snapshot of what the microphone is "hearing" – produced every audio chunk (~23 ms). */
data class AudioFeatures(
    val rms: Float,            // 0..1 raw level
    val bass: Float,           // 0..1 auto-gain-normalised band energy (30-150 Hz)
    val mid: Float,            // 150-1000 Hz
    val high: Float,           // 1-8 kHz
    val kick: Float,           // 0 = none, else strength 0..1 of a bass onset (kick drum / thump)
    val hit: Float,            // 0 = none, else strength 0..1 of any other onset (snare, hat, syllable)
    val bpm: Float,            // 0 until a tempo is locked
    val bpmConfidence: Float   // 0..1
) {
    companion object {
        val SILENT = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}
