package com.chastechgroup.camix.camera

import androidx.camera.core.Camera
import androidx.camera.core.ExposureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure, Android-free exposure maths (unit-tested).
 *
 * The original controller computed an ABSOLUTE exposure-compensation index from the brightness error
 * ("30 brightness units = 1 stop") and treated index units as stops. Two defects followed:
 *  - dark scenes under-corrected (final brightness 55 instead of 130 in simulation) and
 *  - bright scenes "hunted" (the measured brightness already includes the previous correction, so an
 *    absolute P-controller flips between two indices every tick - 14 flips in 7 s in simulation).
 * This version is an INTEGRAL controller in EV units: convert the error to EV (gamma-aware), accumulate
 * a fraction per tick, dead-band, slew-limit to 1 index per tick, convert with the device's real
 * exposureCompensationStep and clamp to +-2 EV. Simulation: zero hunting across scenes 30..240.
 */
object ExposureMath {
    const val GAIN = 0.25
    const val DEAD_BAND_EV = 0.35
    const val MAX_EV = 2.0
    private const val GAMMA = 2.2

    /** Desired mean luma (0..255) for the current scene. */
    fun targetBrightness(
        isNight: Boolean, isBacklit: Boolean, skinRatio: Double,
        brightRatio: Double, darkRatio: Double, hfEnergy: Double
    ): Double = when {
        isNight              -> 145.0
        isBacklit            -> 140.0
        skinRatio > 0.12     -> 150.0
        brightRatio > 0.20   -> 110.0
        darkRatio > 0.60     -> 155.0
        hfEnergy > 0.10      -> 135.0
        else                 -> 130.0
    }

    /** Exposure error in stops (positive = too dark). Brightness is gamma-encoded, hence the 2.2. */
    fun errorEv(target: Double, brightness: Double): Double =
        GAMMA * ln(target / max(brightness, 4.0)) / ln(2.0)

    class Loop(private val stepEv: Double, lo: Int, hi: Int) {
        private val lowest = max(lo, (-MAX_EV / stepEv).toInt())
        private val highest = min(hi, (MAX_EV / stepEv).toInt())
        private var acc = 0.0

        fun reset() { acc = 0.0 }

        /** @return the exposure-compensation index to apply next */
        fun next(currentIndex: Int, target: Double, brightness: Double): Int {
            val err = errorEv(target, brightness)
            if (abs(err) < DEAD_BAND_EV) { acc = 0.0; return currentIndex }
            acc += GAIN * err / stepEv
            var s = acc.toInt()
            if (s == 0) return currentIndex
            acc -= s
            if (s > 1) s = 1
            if (s < -1) s = -1
            return min(highest, max(lowest, currentIndex + s))
        }
    }
}

/**
 * Smart auto-exposure on top of the camera's own AE: nudges the exposure-compensation index so the
 * measured scene brightness approaches a per-scene target (see [ExposureMath]).
 */
class AutoExposureController {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var camera: Camera? = null
    private var exposureState: ExposureState? = null
    private var loop: ExposureMath.Loop? = null
    private var stepEv = 0.0
    private var autoJob: Job? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private val _evIndex = MutableStateFlow(0)
    val evIndex: StateFlow<Int> = _evIndex.asStateFlow()

    private val _exposureLabel = MutableStateFlow("Auto")
    val exposureLabel: StateFlow<String> = _exposureLabel.asStateFlow()

    private val _isClipping = MutableStateFlow(false)
    val isClipping: StateFlow<Boolean> = _isClipping.asStateFlow()

    private val _isTooDark = MutableStateFlow(false)
    val isTooDark: StateFlow<Boolean> = _isTooDark.asStateFlow()

    // ── Scene feed ────────────────────────────────────────────────────────────
    @Volatile private var avgBrightness = 128.0
    @Volatile private var darkRatio = 0.0
    @Volatile private var brightRatio = 0.0
    @Volatile private var skinRatio = 0.0
    @Volatile private var hfEnergyRatio = 0.0
    @Volatile private var isNightScene = false
    @Volatile private var isBacklit = false
    @Volatile private var isManualOverride = false
    private var haveBrightness = false
    private val alpha = 0.35            // stats arrive at ~2 Hz: smooth gently

    fun attach(cam: Camera) {
        camera = cam
        val state = cam.cameraInfo.exposureState
        exposureState = state
        stepEv = try { state.exposureCompensationStep.toDouble() } catch (_: Throwable) { 0.0 }
        val range = state.exposureCompensationRange
        loop = if (state.isExposureCompensationSupported && stepEv > 0.0 && range.lower < range.upper)
            ExposureMath.Loop(stepEv, range.lower, range.upper) else null
        _evIndex.value = state.exposureCompensationIndex
        Timber.d("AutoExposure attached: range=%s step=%.3f supported=%b",
            range, stepEv, state.isExposureCompensationSupported)
        startLoop()
    }

    fun detach() {
        autoJob?.cancel()
        camera = null
        exposureState = null
        loop = null
    }

    fun release() {
        scope.cancel()
    }

    /** Called ~2 Hz by the scene detector. */
    fun updateScene(
        brightness: Double, darkRatio: Double, brightRatio: Double,
        skinRatio: Double, hfEnergy: Double, isNight: Boolean, isBacklit: Boolean
    ) {
        avgBrightness = if (!haveBrightness) { haveBrightness = true; brightness }
                        else alpha * brightness + (1 - alpha) * avgBrightness
        this.darkRatio = darkRatio
        this.brightRatio = brightRatio
        this.skinRatio = skinRatio
        this.hfEnergyRatio = hfEnergy
        this.isNightScene = isNight
        this.isBacklit = isBacklit
    }

    // ── Manual override (Pro mode / user EV slider) ───────────────────────────

    fun setManualEv(index: Int) {
        isManualOverride = true
        val range = exposureState?.exposureCompensationRange
        val clamped = if (range != null) index.coerceIn(range.lower, range.upper) else index
        applyEv(clamped)
        _exposureLabel.value = "Manual EV " + formatEv(clamped)
    }

    fun enableAuto() {
        isManualOverride = false
        loop?.reset()
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private fun startLoop() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                if (!isManualOverride) tick()
                delay(350)
            }
        }
    }

    private fun tick() {
        val l = loop ?: return
        val current = _evIndex.value
        val target = ExposureMath.targetBrightness(
            isNightScene, isBacklit, skinRatio, brightRatio, darkRatio, hfEnergyRatio)
        val next = l.next(current, target, avgBrightness)
        if (next != current) applyEv(next)

        _isClipping.value = brightRatio > 0.15
        _isTooDark.value = avgBrightness < 35 || darkRatio > 0.75
        _exposureLabel.value = when {
            isNightScene       -> "Night Auto"
            isBacklit          -> "Backlit Auto"
            skinRatio > 0.1    -> "Portrait Auto"
            brightRatio > 0.15 -> "HDR Auto"
            else               -> "Smart Auto"
        }
    }

    private fun applyEv(index: Int) {
        _evIndex.value = index
        val future = camera?.cameraControl?.setExposureCompensationIndex(index) ?: return
        future.addListener({
            try { future.get() } catch (t: Throwable) {
                Timber.w("setExposureCompensationIndex(%d) failed: %s", index, t.message)
                // resync with what the camera really has
                exposureState?.let { _evIndex.value = it.exposureCompensationIndex }
            }
        }, Executor { it.run() })
    }

    private fun formatEv(index: Int): String {
        val ev = if (stepEv > 0.0) index * stepEv else index.toDouble()
        val r = (ev * 10).roundToInt() / 10.0
        return if (r >= 0) "+$r" else "$r"
    }
}
