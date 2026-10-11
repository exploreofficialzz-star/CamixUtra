package com.chastechgroup.camix.filter

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs

/**
 * Optional "style" stage that runs AFTER the master colour stage.
 * (Master adjustments + one style = the look you see in the viewfinder, in photos and in video.)
 */
enum class StyleMode {
    NONE, CINEMATIC, THERMAL, ULTRASOUND, PEAKING, PORTRAIT, NIGHT, VIVID, TOON, NEON, DREAM
}

/**
 * Complete filter parameter set for CamixUltra.
 *
 * Master stage (colour / tone / detail) is driven by the "core" fields.
 * The style stage is chosen with [style]; its strength comes from the matching intensity field
 * (cinematicIntensity / thermalIntensity / ultrasoundEdge / focusPeaking* / styleIntensity).
 */
@Parcelize
data class FilterParameters(
    // Core
    val brightness: Float  = 0f,
    val contrast: Float    = 0f,
    val saturation: Float  = 0f,
    val exposure: Float    = 0f,

    // Color
    val warmth: Float      = 0f,   // -1 cool … 1 warm
    val tint: Float        = 0f,   // -1 green … 1 magenta
    val hue: Float         = 0f,   // -180 … 180 deg

    // Tone   (negative highlights = pull highlights down, positive shadows = lift shadows)
    val highlights: Float  = 0f,
    val shadows: Float     = 0f,
    val fade: Float        = 0f,

    // Standard FX
    val vignette: Float    = 0f,
    val sharpen: Float     = 0f,
    val grain: Float       = 0f,
    val blur: Float        = 0f,   // reserved (unused by the current shaders)

    // Detail FX
    val clarityBoost: Float = 0f,   // micro-contrast 0..1
    val dehaze: Float       = 0f,   // atmospheric dehaze 0..1

    // Style stage
    val style: StyleMode          = StyleMode.NONE,
    val styleIntensity: Float     = 0f,   // PORTRAIT / NIGHT / VIVID / TOON / NEON / DREAM

    // Cinematic style
    val cinematicIntensity: Float = 0f,   // 0..1

    // Thermal style
    val thermalIntensity: Float   = 0f,   // 0..1
    val thermalContrast: Float    = 0.3f,

    // Ultrasound / edge style
    val ultrasoundEdge: Float     = 0f,
    val ultrasoundBrightness: Float = 0f,

    // Focus-peaking style
    val focusPeakingEnabled: Boolean = false,
    val focusPeakingThreshold: Float = 0.5f,
    val focusPeakingColor: Float     = 0f,   // 0=red, 0.5=white, 1=yellow

    // AI / scene meta
    val isAutoEnabled: Boolean  = true,
    val detectedScene: SceneType = SceneType.AUTO,
    val confidence: Float       = 0f
) : Parcelable {

    /** True when the master colour stage would not change a pixel. */
    fun masterIsNeutral(): Boolean =
        near0(brightness) && near0(contrast) && near0(saturation) && near0(exposure) &&
        near0(warmth) && near0(tint) && near0(hue / 180f) && near0(highlights) && near0(shadows) &&
        near0(fade) && near0(vignette) && near0(sharpen) && near0(grain) &&
        near0(clarityBoost) && near0(dehaze)

    /** True when neither stage would change a pixel (photo can be saved untouched). */
    fun isNeutral(): Boolean = style == StyleMode.NONE && masterIsNeutral()

    /**
     * Smoothly move every continuous value toward [t] by factor [k] (0..1).
     * Discrete values (style, flags) are taken from the target immediately.
     * Used so AI-Auto parameter changes fade in instead of popping in the viewfinder.
     */
    fun blendTo(t: FilterParameters, k: Float): FilterParameters {
        fun l(a: Float, b: Float): Float = a + (b - a) * k
        return t.copy(
            brightness = l(brightness, t.brightness), contrast = l(contrast, t.contrast),
            saturation = l(saturation, t.saturation), exposure = l(exposure, t.exposure),
            warmth = l(warmth, t.warmth), tint = l(tint, t.tint), hue = l(hue, t.hue),
            highlights = l(highlights, t.highlights), shadows = l(shadows, t.shadows),
            fade = l(fade, t.fade), vignette = l(vignette, t.vignette),
            sharpen = l(sharpen, t.sharpen), grain = l(grain, t.grain),
            clarityBoost = l(clarityBoost, t.clarityBoost), dehaze = l(dehaze, t.dehaze),
            styleIntensity = l(styleIntensity, t.styleIntensity),
            cinematicIntensity = l(cinematicIntensity, t.cinematicIntensity),
            thermalIntensity = l(thermalIntensity, t.thermalIntensity),
            thermalContrast = l(thermalContrast, t.thermalContrast),
            ultrasoundEdge = l(ultrasoundEdge, t.ultrasoundEdge),
            ultrasoundBrightness = l(ultrasoundBrightness, t.ultrasoundBrightness)
        )
    }

    private fun near0(v: Float) = abs(v) < 0.002f

    companion object {
        val DEFAULT = FilterParameters()

        // ── Standard presets ───────────────────────────────────────────────
        val PORTRAIT = FilterParameters(
            brightness = 0.08f, contrast = 0.15f, saturation = 0.1f,
            warmth = 0.12f, highlights = 0.08f, shadows = 0.12f,
            vignette = 0.2f, sharpen = 0.3f, clarityBoost = 0.1f,
            style = StyleMode.PORTRAIT, styleIntensity = 0.55f
        )
        val LANDSCAPE = FilterParameters(
            contrast = 0.22f, saturation = 0.28f, exposure = 0.05f,
            highlights = -0.12f, shadows = 0.18f,
            sharpen = 0.45f, vignette = 0.1f,
            dehaze = 0.15f, clarityBoost = 0.25f
        )
        val NIGHT = FilterParameters(
            brightness = 0.35f, contrast = 0.12f, saturation = -0.08f,
            exposure = 0.45f, warmth = 0.05f,
            highlights = -0.22f, shadows = 0.32f, grain = 0.04f, sharpen = 0.15f,
            style = StyleMode.NIGHT, styleIntensity = 0.7f
        )
        val VIVID = FilterParameters(
            contrast = 0.18f, saturation = 0.12f, exposure = 0.05f,
            sharpen = 0.3f, highlights = -0.05f, clarityBoost = 0.2f,
            style = StyleMode.VIVID, styleIntensity = 0.8f
        )
        val WARM = FilterParameters(
            warmth = 0.42f, saturation = 0.12f, tint = 0.05f, contrast = 0.05f
        )
        val COOL = FilterParameters(
            warmth = -0.3f, saturation = 0.06f, tint = -0.05f, contrast = 0.1f
        )
        val DRAMATIC = FilterParameters(
            contrast = 0.42f, saturation = -0.1f, vignette = 0.45f,
            shadows = 0.22f, highlights = -0.18f, sharpen = 0.55f, clarityBoost = 0.3f
        )
        val FADE = FilterParameters(
            fade = 0.42f, contrast = -0.15f, saturation = -0.22f,
            highlights = 0.1f, grain = 0.1f
        )
        val MONO = FilterParameters(saturation = -1f, contrast = 0.22f, grain = 0.05f)
        val SEPIA = FilterParameters(
            saturation = -0.5f, warmth = 0.62f, contrast = 0.1f, fade = 0.15f, tint = 0.1f
        )

        // ── Style presets ──────────────────────────────────────────────────
        val CINEMATIC = FilterParameters(
            cinematicIntensity = 0.85f, grain = 0.12f,
            saturation = -0.05f, clarityBoost = 0.15f,
            style = StyleMode.CINEMATIC
        )
        val THERMAL = FilterParameters(
            thermalIntensity = 1f, thermalContrast = 0.4f, style = StyleMode.THERMAL
        )
        val ULTRASOUND = FilterParameters(
            ultrasoundEdge = 1.8f, ultrasoundBrightness = 0.1f, style = StyleMode.ULTRASOUND
        )
        val FOCUS_PEAK = FilterParameters(
            focusPeakingEnabled = true, focusPeakingThreshold = 0.5f, focusPeakingColor = 0f,
            style = StyleMode.PEAKING
        )
        val DEHAZE = FilterParameters(
            dehaze = 0.5f, contrast = 0.1f, saturation = 0.1f, sharpen = 0.2f
        )
        val TOON = FilterParameters(style = StyleMode.TOON, styleIntensity = 1f)
        val NEON = FilterParameters(style = StyleMode.NEON, styleIntensity = 1f)
        val DREAM = FilterParameters(
            saturation = 0.08f, style = StyleMode.DREAM, styleIntensity = 0.8f
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Scene types
// ──────────────────────────────────────────────────────────────────────────────
enum class SceneType {
    AUTO, PORTRAIT, LANDSCAPE, NIGHT, LOW_LIGHT, BACKLIT,
    TEXT, FOOD, SUNSET, INDOOR, OUTDOOR, MACRO, ACTION
}

// ──────────────────────────────────────────────────────────────────────────────
// Named presets (drives FilterPanel chips)
// ──────────────────────────────────────────────────────────────────────────────
enum class FilterPreset(val displayName: String) {
    NONE("Original"),
    AUTO("AI Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    NIGHT("Night"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    DRAMATIC("Dramatic"),
    FADE("Fade"),
    MONO("Mono"),
    SEPIA("Sepia"),
    CINEMATIC("Cinematic"),
    THERMAL("Thermal"),
    ULTRASOUND("Ultrasound"),
    FOCUS_PEAK("Focus Peak"),
    DEHAZE("Dehaze"),
    TOON("Toon"),
    NEON("Neon"),
    DREAM("Dream");

    fun toParameters(): FilterParameters = when (this) {
        NONE       -> FilterParameters.DEFAULT.copy(isAutoEnabled = false)
        AUTO       -> FilterParameters.DEFAULT.copy(isAutoEnabled = true)
        PORTRAIT   -> FilterParameters.PORTRAIT.copy(isAutoEnabled = false)
        LANDSCAPE  -> FilterParameters.LANDSCAPE.copy(isAutoEnabled = false)
        NIGHT      -> FilterParameters.NIGHT.copy(isAutoEnabled = false)
        VIVID      -> FilterParameters.VIVID.copy(isAutoEnabled = false)
        WARM       -> FilterParameters.WARM.copy(isAutoEnabled = false)
        COOL       -> FilterParameters.COOL.copy(isAutoEnabled = false)
        DRAMATIC   -> FilterParameters.DRAMATIC.copy(isAutoEnabled = false)
        FADE       -> FilterParameters.FADE.copy(isAutoEnabled = false)
        MONO       -> FilterParameters.MONO.copy(isAutoEnabled = false)
        SEPIA      -> FilterParameters.SEPIA.copy(isAutoEnabled = false)
        CINEMATIC  -> FilterParameters.CINEMATIC.copy(isAutoEnabled = false)
        THERMAL    -> FilterParameters.THERMAL.copy(isAutoEnabled = false)
        ULTRASOUND -> FilterParameters.ULTRASOUND.copy(isAutoEnabled = false)
        FOCUS_PEAK -> FilterParameters.FOCUS_PEAK.copy(isAutoEnabled = false)
        DEHAZE     -> FilterParameters.DEHAZE.copy(isAutoEnabled = false)
        TOON       -> FilterParameters.TOON.copy(isAutoEnabled = false)
        NEON       -> FilterParameters.NEON.copy(isAutoEnabled = false)
        DREAM      -> FilterParameters.DREAM.copy(isAutoEnabled = false)
    }
}
