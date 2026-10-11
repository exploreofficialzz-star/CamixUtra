package com.chastechgroup.camix.filter

import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time scene detector for CamixUltra.
 *
 *  - Reads the camera's YUV_420_888 planes DIRECTLY (stride-aware, ~12 000 sample points). The previous
 *    implementation pushed only the luma plane through a "YUV -> JPEG -> Bitmap" round trip, which returned
 *    garbage colour statistics and allocated several MB every analysis tick.
 *  - Produces smoothed ImageStats, a scene type (with a stability gate), filter suggestions for AI Auto and a
 *    REAL RGB histogram for the histogram overlay (the old overlay drew a fixed bell curve).
 *
 * Runs inline on the CameraX analysis thread (~1 ms per analysed frame, every 12th frame).
 */
class SceneDetector {

    /** 64-bin histograms, each normalised so its largest bin is 1.0 */
    class HistogramData(val r: FloatArray, val g: FloatArray, val b: FloatArray, val luma: FloatArray)

    private val _detectedScene   = MutableStateFlow(SceneType.AUTO)
    val detectedScene: StateFlow<SceneType> = _detectedScene.asStateFlow()

    private val _suggestedParams = MutableStateFlow(FilterParameters.DEFAULT)
    val suggestedParams: StateFlow<FilterParameters> = _suggestedParams.asStateFlow()

    private val _sceneStats = MutableStateFlow<ImageStats?>(null)
    val sceneStats: StateFlow<ImageStats?> = _sceneStats.asStateFlow()

    private val _histogram = MutableStateFlow<HistogramData?>(null)
    val histogram: StateFlow<HistogramData?> = _histogram.asStateFlow()

    @Volatile var enabled: Boolean = true

    private var frameCounter = 0
    private val skipFrames = 12                       // ~2 Hz at 24-30 fps

    // IIR smoothing (initialised from the first real frame)
    private var haveSmooth = false
    private var smoothBrightness = 0.0
    private var smoothContrast = 0.0
    private var smoothSaturation = 0.0
    private var lastScene = SceneType.AUTO
    private var sceneStability = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /** Always closes [image]. */
    fun processFrame(image: ImageProxy) {
        try {
            frameCounter++
            if (!enabled || frameCounter % skipFrames != 0) return
            val stats = sample(image) ?: return
            analyze(stats)
        } catch (e: Exception) {
            Timber.e(e, "SceneDetector: analysis error")
        } finally {
            image.close()
        }
    }

    fun release() { enabled = false }

    // ── Sampling ──────────────────────────────────────────────────────────────

    private fun byteAt(buf: ByteBuffer, index: Int): Int =
        if (index >= 0 && index < buf.limit()) buf.get(index).toInt() and 0xFF else 128

    private fun sample(image: ImageProxy): ImageStats? {
        val planes = image.planes
        if (planes.size < 3) return null
        val w = image.width
        val h = image.height
        if (w < 16 || h < 16) return null

        val yBuf = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride

        val stepX = max(1, w / 120)
        val stepY = max(1, h / 96)

        val lumaHist = IntArray(256)
        val rHist = IntArray(64)
        val gHist = IntArray(64)
        val bHist = IntArray(64)

        var n = 0
        var sumL = 0.0; var sumS = 0.0; var sumW = 0.0
        var dark = 0; var bright = 0; var skin = 0
        var hf = 0.0

        var y = 0
        while (y < h) {
            var prevL = -1.0
            var x = 0
            while (x < w) {
                val yy = byteAt(yBuf, y * yRow + x * yPix)
                val cx = x shr 1
                val cy = y shr 1
                val u = byteAt(uBuf, cy * uRow + cx * uPix) - 128
                val v = byteAt(vBuf, cy * vRow + cx * vPix) - 128

                val r = clamp255(yy + 1.402 * v)
                val g = clamp255(yy - 0.344136 * u - 0.714136 * v)
                val b = clamp255(yy + 1.772 * u)

                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val li = min(255, max(0, lum.toInt()))
                lumaHist[li]++
                rHist[r shr 2]++; gHist[g shr 2]++; bHist[b shr 2]++

                sumL += lum
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                sumS += if (mx > 0) (mx - mn).toDouble() / mx else 0.0
                sumW += (r - b) / 255.0
                if (lum < 50) dark++
                if (lum > 200) bright++

                val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
                val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
                if (cr in 140.0..180.0 && cb in 90.0..130.0) skin++

                if (prevL >= 0.0) hf += abs(lum - prevL)
                prevL = lum
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return null

        // robust contrast: 99.5th - 0.5th percentile of the luma histogram
        val lowCut = (n * 0.005).toInt()
        var acc = 0; var p0 = 0
        for (i in 0 until 256) { acc += lumaHist[i]; if (acc > lowCut) { p0 = i; break } }
        acc = 0; var p1 = 255
        for (i in 255 downTo 0) { acc += lumaHist[i]; if (acc > lowCut) { p1 = i; break } }

        var mid = 0
        for (i in 85..170) mid += lumaHist[i]

        publishHistogram(lumaHist, rHist, gHist, bHist)

        return ImageStats(
            avgBrightness   = sumL / n,
            avgSaturation   = sumS / n,
            avgWarmth       = sumW / n,
            contrast        = (p1 - p0).toDouble(),
            skinRatio       = skin.toDouble() / n,
            darkRatio       = dark.toDouble() / n,
            brightRatio     = bright.toDouble() / n,
            exposureBalance = mid.toDouble() / n,
            minBrightness   = p0.toDouble(),
            maxBrightness   = p1.toDouble(),
            hfEnergyRatio   = hf / n / 255.0
        )
    }

    private fun clamp255(v: Double): Int = if (v < 0.0) 0 else if (v > 255.0) 255 else v.toInt()

    private fun publishHistogram(luma: IntArray, r: IntArray, g: IntArray, b: IntArray) {
        val l64 = IntArray(64)
        for (i in 0 until 256) l64[i shr 2] += luma[i]
        _histogram.value = HistogramData(norm(r), norm(g), norm(b), norm(l64))
    }

    private fun norm(h: IntArray): FloatArray {
        var m = 1
        for (v in h) if (v > m) m = v
        return FloatArray(h.size) { h[it].toFloat() / m }
    }

    // ── Analysis ──────────────────────────────────────────────────────────────

    private fun analyze(stats: ImageStats) {
        val a = 0.25
        if (!haveSmooth) {
            haveSmooth = true
            smoothBrightness = stats.avgBrightness
            smoothContrast = stats.contrast
            smoothSaturation = stats.avgSaturation
        } else {
            smoothBrightness = a * stats.avgBrightness + (1 - a) * smoothBrightness
            smoothContrast   = a * stats.contrast       + (1 - a) * smoothContrast
            smoothSaturation = a * stats.avgSaturation  + (1 - a) * smoothSaturation
        }
        val smoothed = stats.copy(
            avgBrightness = smoothBrightness,
            contrast = smoothContrast,
            avgSaturation = smoothSaturation
        )

        val scene = detectScene(smoothed)
        if (scene == lastScene) sceneStability++ else { sceneStability = 0; lastScene = scene }
        val committed = if (sceneStability >= 3) scene else _detectedScene.value

        _detectedScene.value = committed
        _sceneStats.value = smoothed
        _suggestedParams.value = computeParams(committed, smoothed)
    }

    private fun detectScene(s: ImageStats): SceneType = when {
        s.skinRatio in 0.07..0.70
                                        -> SceneType.PORTRAIT
        s.avgBrightness < 30 && s.darkRatio > 0.65
                                        -> SceneType.NIGHT
        s.avgBrightness < 70 && s.darkRatio > 0.40
                                        -> SceneType.LOW_LIGHT
        s.brightRatio > 0.30 && s.contrast > 145
                                        -> SceneType.BACKLIT
        s.avgWarmth > 0.15 && s.avgBrightness in 55.0..155.0 && s.avgSaturation > 0.28
                                        -> SceneType.SUNSET
        s.avgSaturation > 0.32 && s.avgBrightness in 75.0..185.0 && s.hfEnergyRatio > 0.04
                                        -> SceneType.FOOD
        s.avgSaturation < 0.13 && s.contrast > 175
                                        -> SceneType.TEXT
        s.hfEnergyRatio > 0.12 && s.avgBrightness in 60.0..180.0
                                        -> SceneType.MACRO
        s.avgBrightness in 80.0..200.0 && s.contrast in 75.0..185.0 && s.avgSaturation > 0.22
                                        -> SceneType.LANDSCAPE
        s.avgBrightness in 45.0..125.0 && s.avgWarmth > 0.01
                                        -> SceneType.INDOOR
        s.avgBrightness > 145 && s.avgSaturation > 0.13
                                        -> SceneType.OUTDOOR
        s.contrast > 145                -> SceneType.ACTION
        else                            -> SceneType.AUTO
    }

    private fun computeParams(scene: SceneType, s: ImageStats): FilterParameters {
        val b = s.avgBrightness; val sat = s.avgSaturation; val w = s.avgWarmth; val c = s.contrast
        return when (scene) {
            SceneType.PORTRAIT  -> FilterParameters(
                brightness = if (b < 100) 0.14f else 0.05f,
                contrast = 0.15f, saturation = if (sat < 0.3) 0.14f else 0f,
                warmth = if (w < 0) 0.1f else 0f,
                highlights = 0.1f, shadows = 0.1f,
                vignette = 0.2f, sharpen = 0.3f, clarityBoost = 0.08f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.88f)

            SceneType.LANDSCAPE -> FilterParameters(
                brightness = if (b < 120) 0.1f else 0f,
                contrast = 0.22f, saturation = if (sat < 0.35) 0.26f else 0.1f,
                exposure = if (b < 100) 0.1f else 0f,
                highlights = -0.12f, shadows = 0.16f,
                sharpen = 0.42f, vignette = 0.1f,
                dehaze = if (b > 140) 0.12f else 0f, clarityBoost = 0.22f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.82f)

            SceneType.NIGHT     -> FilterParameters(
                brightness = 0.38f, contrast = 0.14f, saturation = -0.1f, exposure = 0.48f,
                warmth = 0.05f, highlights = -0.24f, shadows = 0.34f,
                grain = 0.04f, sharpen = 0.15f,
                style = StyleMode.NIGHT, styleIntensity = 0.7f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.92f)

            SceneType.LOW_LIGHT -> FilterParameters(
                brightness = 0.24f, contrast = 0.1f, exposure = 0.3f,
                shadows = 0.24f, highlights = -0.14f,
                saturation = if (sat < 0.2) 0.1f else 0f, grain = 0.03f,
                style = StyleMode.NIGHT, styleIntensity = 0.45f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.86f)

            SceneType.BACKLIT   -> FilterParameters(
                brightness = 0.22f, contrast = 0.15f, exposure = 0.22f,
                highlights = -0.32f, shadows = 0.32f, saturation = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.89f)

            SceneType.SUNSET    -> FilterParameters(
                brightness = 0.05f, contrast = 0.2f, saturation = 0.22f,
                warmth = 0.28f, highlights = -0.1f, shadows = 0.1f, vignette = 0.15f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.84f)

            SceneType.FOOD      -> FilterParameters(
                brightness = 0.1f, contrast = 0.16f, saturation = 0.32f,
                warmth = 0.14f, sharpen = 0.38f, highlights = -0.05f, clarityBoost = 0.15f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.78f)

            SceneType.TEXT      -> FilterParameters(
                contrast = 0.32f, sharpen = 0.62f,
                brightness = if (b < 100) 0.14f else 0f, saturation = -0.5f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.74f)

            SceneType.MACRO     -> FilterParameters(
                contrast = 0.22f, sharpen = 0.55f, saturation = 0.16f,
                brightness = 0.05f, vignette = 0.15f, clarityBoost = 0.3f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.76f)

            SceneType.INDOOR    -> FilterParameters(
                brightness = if (b < 100) 0.14f else 0f, contrast = 0.1f,
                warmth = if (w < 0) 0.14f else 0.05f,
                saturation = if (sat < 0.25) 0.1f else 0f, shadows = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.76f)

            SceneType.OUTDOOR   -> FilterParameters(
                contrast = 0.16f, saturation = if (sat < 0.3) 0.14f else 0f,
                highlights = -0.1f, sharpen = 0.26f, clarityBoost = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.80f)

            SceneType.ACTION    -> FilterParameters(
                contrast = 0.26f, sharpen = 0.52f, saturation = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.72f)

            SceneType.AUTO      -> FilterParameters(
                brightness = if (b < 80) 0.14f else if (b > 180) -0.05f else 0f,
                contrast   = if (c < 80) 0.1f else 0f,
                saturation = if (sat < 0.2) 0.1f else 0f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.5f)
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class ImageStats(
        val avgBrightness: Double,
        val avgSaturation: Double,
        val avgWarmth: Double,
        val contrast: Double,
        val skinRatio: Double,
        val darkRatio: Double,
        val brightRatio: Double,
        val exposureBalance: Double,
        val minBrightness: Double,
        val maxBrightness: Double,
        val hfEnergyRatio: Double = 0.0
    )
}
