package com.chastechgroup.camix.filter

/**
 * GLSL ES 1.00 shaders for every real-time effect.
 *
 * Pipeline (all passes share VERTEX_SHADER_DEFAULT):
 *   OES_COPY  : camera external texture  -> upright 2-D texture (rotation / crop done by the MVP matrix)
 *   MASTER    : colour, tone, detail      (skipped when every slider is neutral)
 *   STYLE     : one of CINEMATIC / THERMAL / ULTRASOUND / PEAKING / PORTRAIT / NIGHT / VIVID / TOON / NEON / DREAM
 *   PULSE     : audio-reactive composite (zoom punch, RGB split, glitch, strobe, hue spin, VHS)
 *
 * Because the MASTER / STYLE / PULSE passes read an already-upright 2-D texture, every directional
 * effect (letterbox, streaks, edge detection) works in screen space and the very same code is used
 * for the live viewfinder, recorded video and full-resolution photos.
 */
object CameraFilterShaders {

    // ──────────────────────────────────────────────────────────────────────────
    // Vertex shader (shared by all programs)
    // ──────────────────────────────────────────────────────────────────────────
    const val VERTEX_SHADER_DEFAULT = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
        }
    """

    private const val PRECISION =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"

    private const val HEADER_2D =
        PRECISION + "varying vec2 vTextureCoord;\nuniform sampler2D uTexture;\n"

    // ── Shared snippets ───────────────────────────────────────────────────────

    private const val GLSL_HSV = """
        vec3 rgbToHsv(vec3 rgb) {
            float mx = max(max(rgb.r, rgb.g), rgb.b);
            float mn = min(min(rgb.r, rgb.g), rgb.b);
            float d  = mx - mn;
            float h  = 0.0;
            if (d > 0.0001) {
                if (mx == rgb.r)      h = mod((rgb.g - rgb.b) / d, 6.0);
                else if (mx == rgb.g) h = (rgb.b - rgb.r) / d + 2.0;
                else                  h = (rgb.r - rgb.g) / d + 4.0;
                h = h / 6.0;
            }
            float s = 0.0;
            if (mx > 0.0001) s = d / mx;
            return vec3(h, s, mx);
        }

        vec3 hsvToRgb(vec3 hsv) {
            float h = hsv.x * 6.0;
            float c = hsv.z * hsv.y;
            float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
            float m = hsv.z - c;
            vec3 rgb;
            if      (h < 1.0) rgb = vec3(c, x, 0.0);
            else if (h < 2.0) rgb = vec3(x, c, 0.0);
            else if (h < 3.0) rgb = vec3(0.0, c, x);
            else if (h < 4.0) rgb = vec3(0.0, x, c);
            else if (h < 5.0) rgb = vec3(x, 0.0, c);
            else              rgb = vec3(c, 0.0, x);
            return rgb + m;
        }
    """

    /** needs: uTexture, uImageWidth, uImageHeight */
    private const val GLSL_SOBEL = """
        float lumAt(vec2 uv) {
            return dot(texture2D(uTexture, uv).rgb, vec3(0.2126, 0.7152, 0.0722));
        }
        float sobelEdge(vec2 uv) {
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            float tl = lumAt(uv + tx * vec2(-1.0,  1.0));
            float tc = lumAt(uv + tx * vec2( 0.0,  1.0));
            float tr = lumAt(uv + tx * vec2( 1.0,  1.0));
            float cl = lumAt(uv + tx * vec2(-1.0,  0.0));
            float cr = lumAt(uv + tx * vec2( 1.0,  0.0));
            float bl = lumAt(uv + tx * vec2(-1.0, -1.0));
            float bc = lumAt(uv + tx * vec2( 0.0, -1.0));
            float br = lumAt(uv + tx * vec2( 1.0, -1.0));
            float gx = (-tl - 2.0 * cl - bl) + (tr + 2.0 * cr + br);
            float gy = (-tl - 2.0 * tc - tr) + (bl + 2.0 * bc + br);
            return length(vec2(gx, gy));
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // OES COPY — camera frame (external texture) -> upright 2-D texture
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_OES_COPY =
        "#extension GL_OES_EGL_image_external : require\n" + PRECISION + """
        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = vec4(texture2D(uTexture, vTextureCoord).rgb, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // MASTER SHADER — all standard photo / video corrections
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_MASTER = HEADER_2D + """
        uniform float uBrightness;   // additive, -1 to 1
        uniform float uContrast;     // delta around 0.5
        uniform float uSaturation;   // delta
        uniform float uExposure;     // EV stops
        uniform float uWarmth;       // -1 cool .. 1 warm
        uniform float uTint;         // -1 green .. 1 magenta
        uniform float uHue;          // degrees
        uniform float uHighlights;   // negative = pull highlights down
        uniform float uShadows;      // positive = lift shadows
        uniform float uFade;
        uniform float uVignette;
        uniform float uSharpen;
        uniform float uGrain;
        uniform float uClarityBoost;
        uniform float uDehaze;
        uniform float uImageWidth;
        uniform float uImageHeight;
        uniform float uTime;

        const vec3 LUM  = vec3(0.2126, 0.7152, 0.0722);
        const vec3 WARM = vec3(1.0, 0.78, 0.46);
        const vec3 COOL = vec3(0.46, 0.67, 1.0);

        float random(vec2 c) {
            return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453);
        }

        float minc(vec3 v) { return min(min(v.r, v.g), v.b); }

        vec3 tex(vec2 uv) { return texture2D(uTexture, uv).rgb; }
    """ + GLSL_HSV + """
        void main() {
            vec2 uv = vTextureCoord;
            vec2 px = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            vec3 c  = tex(uv);

            // Detail stage: sharpen + clarity + dehaze share the same 8 taps and all work on 'c'
            if (uSharpen > 0.01 || uClarityBoost > 0.01 || uDehaze > 0.01) {
                vec3 t1 = tex(uv + vec2( px.x, 0.0));
                vec3 t2 = tex(uv - vec2( px.x, 0.0));
                vec3 t3 = tex(uv + vec2(0.0,  px.y));
                vec3 t4 = tex(uv - vec2(0.0,  px.y));
                vec3 d1 = tex(uv + px * vec2(-3.0, -3.0));
                vec3 d2 = tex(uv + px * vec2( 3.0, -3.0));
                vec3 d3 = tex(uv + px * vec2(-3.0,  3.0));
                vec3 d4 = tex(uv + px * vec2( 3.0,  3.0));
                vec3 fine = c - (t1 + t2 + t3 + t4) * 0.25;
                vec3 wide = c - (d1 + d2 + d3 + d4) * 0.25;

                if (uDehaze > 0.01) {
                    float dark = min(minc(c), min(min(minc(t1), minc(t2)), min(minc(t3), minc(t4))));
                    dark = min(dark, min(min(minc(d1), minc(d2)), min(minc(d3), minc(d4))));
                    float tr  = clamp(1.0 - uDehaze * dark * 0.95, 0.15, 1.0);
                    vec3 air  = vec3(0.86, 0.88, 0.92);
                    c = clamp((c - air) / tr + air, 0.0, 1.0);
                }
                c += fine * uSharpen * 1.1;
                float lm = dot(c, LUM);
                c += wide * uClarityBoost * (1.0 - abs(lm * 2.0 - 1.0)) * 1.6;
            }

            // Exposure (EV), brightness, contrast
            c *= pow(2.0, uExposure);
            c += uBrightness;
            c = (c - 0.5) * (1.0 + uContrast) + 0.5;

            // Shadows lift / highlights roll-off
            c = c + uShadows * (1.0 - c) * (1.0 - c);
            c = c + uHighlights * c * c;
            c = clamp(c, 0.0, 1.0);

            // Saturation
            float lum = dot(c, LUM);
            c = mix(vec3(lum), c, 1.0 + uSaturation);

            // Hue rotation
            if (abs(uHue) > 0.5) {
                vec3 hsv = rgbToHsv(clamp(c, 0.0, 1.0));
                hsv.x = mod(hsv.x + uHue / 360.0 + 1.0, 1.0);
                c = hsvToRgb(hsv);
            }

            // Warmth
            if (abs(uWarmth) > 0.001) {
                vec3 wc = mix(COOL, WARM, uWarmth * 0.5 + 0.5);
                c = mix(c, c * wc, abs(uWarmth) * 0.7);
            }

            // Tint (green <-> magenta)
            if (abs(uTint) > 0.001) {
                c.g  = mix(c.g,  c.g  * (1.0 + uTint * 0.3),  abs(uTint));
                c.rb = mix(c.rb, c.rb * (1.0 - uTint * 0.15), abs(uTint));
            }

            // Fade
            if (uFade > 0.01) {
                c = mix(c, c * (1.0 - uFade * 0.3) + uFade * 0.2, uFade);
            }

            // Vignette (edge0 < edge1 as the GLSL spec requires)
            if (uVignette > 0.01) {
                float d = length(uv - 0.5);
                c *= 1.0 - uVignette * 0.8 * smoothstep(0.25, 0.75, d);
            }

            // Film grain
            if (uGrain > 0.01) {
                float g = random(uv + vec2(fract(uTime * 0.37), fract(uTime * 0.73))) - 0.5;
                c += g * uGrain * 0.15;
            }

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // CINEMATIC — filmic S-curve, teal/orange split-tone, anamorphic streak, letterbox
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_CINEMATIC = HEADER_2D + """
        uniform float uIntensity;
        uniform float uGrain;
        uniform float uTime;
        uniform float uImageWidth;
        uniform float uImageHeight;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        float random(vec2 c) {
            return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453);
        }

        vec3 aces(vec3 x) {
            const float A = 2.51;
            const float B = 0.03;
            const float C = 2.43;
            const float D = 0.59;
            const float E = 0.14;
            return clamp((x * (A * x + B)) / (x * (C * x + D) + E), 0.0, 1.0);
        }

        void main() {
            vec2 uv = vTextureCoord;

            // Letterbox: 2.39:1 on landscape frames, capped to 14% bars on portrait frames
            float ar   = uImageWidth / uImageHeight;
            float barH = clamp(0.5 * (1.0 - ar / 2.39), 0.0, 0.14);
            if (uv.y < barH || uv.y > 1.0 - barH) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec3 c = texture2D(uTexture, uv).rgb;
            float lum = dot(c, LUM);
            c = mix(c, vec3(lum), 0.08 * uIntensity);

            // Teal shadows / orange highlights
            float shadow    = clamp((0.4 - lum) * 2.5, 0.0, 1.0);
            float highlight = clamp((lum - 0.6) * 2.5, 0.0, 1.0);
            c += shadow    * vec3(-0.02, 0.02,  0.05) * uIntensity;
            c += highlight * vec3( 0.05, 0.02, -0.03) * uIntensity;

            // ACES tone map
            c = mix(c, aces(c * 1.1), uIntensity * 0.8);

            // Anamorphic horizontal streak on bright highlights (9 taps)
            vec3 streak = vec3(0.0);
            for (int i = -4; i <= 4; i++) {
                float fi = float(i);
                vec3 s = texture2D(uTexture, uv + vec2(fi * 2.0 / uImageWidth, 0.0)).rgb;
                float w = max(0.0, dot(s, LUM) - 0.72) * (1.0 - abs(fi) / 5.0);
                streak += s * w * vec3(0.3, 0.5, 1.0);
            }
            c += streak * uIntensity * 0.45;

            // Vignette + grain
            float d = length(uv - 0.5);
            c *= 1.0 - 0.3 * uIntensity * smoothstep(0.3, 0.8, d);
            float g = random(uv + vec2(fract(uTime * 0.31), fract(uTime * 0.17))) - 0.5;
            c += g * uGrain * 0.1;

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // THERMAL — iron-bow false-colour palette
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_THERMAL = HEADER_2D + """
        uniform float uIntensity;   // blend with original 0..1
        uniform float uContrast;    // thermal contrast boost

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        vec3 thermalPalette(float t) {
            t = clamp(t, 0.0, 1.0);
            vec3 col;
            if      (t < 0.16) col = mix(vec3(0.0, 0.0, 0.0),   vec3(0.0, 0.0, 0.75),  t / 0.16);
            else if (t < 0.33) col = mix(vec3(0.0, 0.0, 0.75),  vec3(0.0, 0.75, 0.75), (t - 0.16) / 0.17);
            else if (t < 0.50) col = mix(vec3(0.0, 0.75, 0.75), vec3(0.0, 0.75, 0.0),  (t - 0.33) / 0.17);
            else if (t < 0.67) col = mix(vec3(0.0, 0.75, 0.0),  vec3(0.9, 0.9, 0.0),   (t - 0.50) / 0.17);
            else if (t < 0.84) col = mix(vec3(0.9, 0.9, 0.0),   vec3(0.9, 0.2, 0.0),   (t - 0.67) / 0.17);
            else               col = mix(vec3(0.9, 0.2, 0.0),   vec3(1.0, 1.0, 1.0),   (t - 0.84) / 0.16);
            return col;
        }

        void main() {
            vec3 c = texture2D(uTexture, vTextureCoord).rgb;
            float lum = dot(c, LUM);
            lum = clamp((lum - 0.5) * (1.0 + uContrast) + 0.5, 0.0, 1.0);
            vec3 result = mix(c, thermalPalette(lum), clamp(uIntensity, 0.0, 1.0));
            gl_FragColor = vec4(result, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // ULTRASOUND / EDGE — Sobel 3x3 over a dark greyscale background
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_ULTRASOUND = HEADER_2D + """
        uniform float uEdgeStrength;
        uniform float uBrightness;
        uniform float uImageWidth;
        uniform float uImageHeight;
    """ + GLSL_SOBEL + """
        void main() {
            vec2 uv = vTextureCoord;
            float edge  = clamp(sobelEdge(uv) * uEdgeStrength, 0.0, 1.0);
            float bgLum = clamp(lumAt(uv) * 0.3 + uBrightness * 0.2, 0.0, 1.0);
            vec3 col = vec3(bgLum) + edge * vec3(0.85, 0.95, 1.0);
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // FOCUS PEAKING — Laplacian edge overlay
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_FOCUS_PEAKING = HEADER_2D + """
        uniform float uThreshold;     // 0..1
        uniform float uPeakingColor;  // 0=red, 0.5=white, 1=yellow
        uniform float uImageWidth;
        uniform float uImageHeight;
    """ + GLSL_SOBEL + """
        void main() {
            vec2 uv = vTextureCoord;
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);

            float center = lumAt(uv);
            float edge = abs(4.0 * center
                - lumAt(uv + vec2(tx.x, 0.0))
                - lumAt(uv - vec2(tx.x, 0.0))
                - lumAt(uv + vec2(0.0, tx.y))
                - lumAt(uv - vec2(0.0, tx.y)));

            vec3 base = texture2D(uTexture, uv).rgb;
            float hit = step(uThreshold * 0.08, edge);

            vec3 peakColor;
            if (uPeakingColor < 0.33)      peakColor = vec3(1.0, 0.05, 0.05);
            else if (uPeakingColor < 0.66) peakColor = vec3(1.0, 1.0, 1.0);
            else                           peakColor = vec3(1.0, 0.95, 0.0);

            gl_FragColor = vec4(mix(base, peakColor, 0.75 * hit), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // PORTRAIT — skin-aware soft focus + centre-weighted background defocus
    // (colour/skin heuristics only: this is NOT depth- or segmentation-based)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_PORTRAIT = HEADER_2D + """
        uniform float uBlurAmount;
        uniform float uSkinSmooth;
        uniform float uImageWidth;
        uniform float uImageHeight;

        vec3 tex(vec2 uv) { return texture2D(uTexture, uv).rgb; }

        float skinMask(vec3 rgb) {
            float cb = 0.5 + (-0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b);
            float cr = 0.5 + ( 0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b);
            float a = 1.0 - smoothstep(0.04, 0.12, abs(cb - 0.455));
            float b = 1.0 - smoothstep(0.04, 0.14, abs(cr - 0.600));
            return a * b;
        }

        vec3 ring(vec2 uv, float r) {
            vec2 px = r / vec2(uImageWidth, uImageHeight);
            vec3 a = tex(uv);
            a += tex(uv + px * vec2( 1.0,  0.0));
            a += tex(uv + px * vec2(-1.0,  0.0));
            a += tex(uv + px * vec2( 0.0,  1.0));
            a += tex(uv + px * vec2( 0.0, -1.0));
            a += tex(uv + px * vec2( 0.7,  0.7));
            a += tex(uv + px * vec2(-0.7,  0.7));
            a += tex(uv + px * vec2( 0.7, -0.7));
            a += tex(uv + px * vec2(-0.7, -0.7));
            return a / 9.0;
        }

        void main() {
            vec2 uv   = vTextureCoord;
            vec3 orig = tex(uv);
            float skin = skinMask(orig);

            float r1 = 2.0 + 7.0 * uBlurAmount;
            vec3 bgBlur    = (ring(uv, r1) + ring(uv, r1 * 2.0)) * 0.5;
            vec3 smallBlur = ring(uv, 1.6);

            vec2 e = (uv - vec2(0.5, 0.46)) * vec2(1.0, 0.82);
            float focus = 1.0 - smoothstep(0.26, 0.62, length(e));
            float bgAmt = clamp((1.0 - focus) * (1.0 - 0.85 * skin) * (0.55 + uBlurAmount), 0.0, 1.0);

            vec3 result = mix(orig, bgBlur, bgAmt);
            result = mix(result, smallBlur, clamp(skin * uSkinSmooth * 0.5, 0.0, 1.0));
            gl_FragColor = vec4(result, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // NIGHT — edge-preserving 3x3 denoise
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_NIGHT = HEADER_2D + """
        uniform float uNoiseReduction;
        uniform float uImageWidth;
        uniform float uImageHeight;

        void main() {
            vec2 uv = vTextureCoord;
            vec2 px = vec2(1.0 / uImageWidth, 1.0 / uImageHeight) * 2.0;
            vec3 c0 = texture2D(uTexture, uv).rgb;
            vec3 acc = vec3(0.0);
            float ws = 0.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec3 s = texture2D(uTexture, uv + px * vec2(float(x), float(y))).rgb;
                    vec3 d = s - c0;
                    float w = exp(-dot(d, d) / 0.02);
                    acc += s * w;
                    ws  += w;
                }
            }
            vec3 dn = acc / ws;
            vec3 c  = mix(c0, dn, clamp(uNoiseReduction, 0.0, 1.0));
            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // VIVID — vibrance-aware saturation boost (protects skin tones)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_VIVID = HEADER_2D + """
        uniform float uIntensity;
    """ + GLSL_HSV + """
        void main() {
            vec3 rgb = texture2D(uTexture, vTextureCoord).rgb;
            vec3 hsv = rgbToHsv(rgb);

            float vibranceBoost = (1.0 - hsv.y) * uIntensity * 0.6;
            hsv.y = min(hsv.y + vibranceBoost, 1.0);

            float skinProtect = 1.0 - smoothstep(0.02, 0.12, abs(hsv.x - 0.05));
            hsv.y = mix(hsv.y, hsv.y * (1.0 - uIntensity * 0.4), skinProtect);

            rgb = hsvToRgb(hsv);
            rgb = (rgb - 0.5) * (1.0 + uIntensity * 0.15) + 0.5;
            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // TOON — posterised colour + ink outlines (comic / anime look)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_TOON = HEADER_2D + """
        uniform float uIntensity;
        uniform float uImageWidth;
        uniform float uImageHeight;
    """ + GLSL_SOBEL + """
        void main() {
            vec2 uv = vTextureCoord;
            vec3 c  = texture2D(uTexture, uv).rgb;
            float edge = sobelEdge(uv);

            vec3 q = floor(c * 5.0 + 0.5) / 5.0;
            float l = dot(q, vec3(0.2126, 0.7152, 0.0722));
            q = mix(vec3(l), q, 1.0 + 0.35 * uIntensity);

            float ink = smoothstep(0.35, 0.9, edge * 1.6);
            vec3 toon = mix(q, vec3(0.05, 0.04, 0.08), ink);
            gl_FragColor = vec4(clamp(mix(c, toon, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // NEON — glowing, colour-cycling edges on a darkened frame
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_NEON = HEADER_2D + """
        uniform float uIntensity;
        uniform float uTime;
        uniform float uImageWidth;
        uniform float uImageHeight;
    """ + GLSL_SOBEL + """
        void main() {
            vec2 uv = vTextureCoord;
            vec3 c  = texture2D(uTexture, uv).rgb;
            float e = clamp(sobelEdge(uv) * 2.2, 0.0, 1.0);

            vec3 neon = 0.5 + 0.5 * cos(6.2831853 * (vec3(0.0, 0.33, 0.67) + uv.y * 0.7 + uTime * 0.12));
            vec3 outc = c * 0.18 + neon * e * 1.35;
            gl_FragColor = vec4(clamp(mix(c, outc, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // DREAM — Orton-style bloom / soft glow
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_DREAM = HEADER_2D + """
        uniform float uIntensity;
        uniform float uImageWidth;
        uniform float uImageHeight;

        vec3 tex(vec2 uv) { return texture2D(uTexture, uv).rgb; }

        void main() {
            vec2 uv = vTextureCoord;
            vec2 px = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            vec3 c = tex(uv);

            vec3 b = c;
            b += tex(uv + px * vec2( 4.0,  0.0));
            b += tex(uv + px * vec2(-4.0,  0.0));
            b += tex(uv + px * vec2( 0.0,  4.0));
            b += tex(uv + px * vec2( 0.0, -4.0));
            b += tex(uv + px * vec2( 3.0,  3.0));
            b += tex(uv + px * vec2(-3.0,  3.0));
            b += tex(uv + px * vec2( 3.0, -3.0));
            b += tex(uv + px * vec2(-3.0, -3.0));

            vec3 o = tex(uv + px * vec2( 10.0,  0.0));
            o += tex(uv + px * vec2(-10.0,  0.0));
            o += tex(uv + px * vec2(  0.0, 10.0));
            o += tex(uv + px * vec2(  0.0, -10.0));
            o += tex(uv + px * vec2(  7.0,  7.0));
            o += tex(uv + px * vec2( -7.0,  7.0));
            o += tex(uv + px * vec2(  7.0, -7.0));
            o += tex(uv + px * vec2( -7.0, -7.0));

            vec3 blur = (b + o) / 17.0;
            vec3 glow = max(blur - 0.40, 0.0) * 1.5;
            vec3 outc = c + glow * vec3(1.0, 0.9, 1.0) * uIntensity;
            outc = mix(outc, blur * 1.05, 0.18 * uIntensity);
            outc = mix(outc, outc * outc * (3.0 - 2.0 * outc), 0.25 * uIntensity);
            gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // PULSE — audio-reactive composite (runs last; also does display crop / mirror via the MVP)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_PULSE = HEADER_2D + """
        uniform float uZoom;        // >= 1, zoom punch about the centre
        uniform vec2  uShake;       // uv-space camera shake
        uniform float uRgb;         // chromatic-aberration distance (uv)
        uniform float uRgbAngle;    // direction of the split (radians)
        uniform float uGlitch;      // 0..1 horizontal block glitch
        uniform float uFlash;       // additive strobe
        uniform float uHue;         // radians
        uniform float uSat;         // saturation multiplier
        uniform float uBass;        // 0..1 smoothed bass energy (vignette pulse)
        uniform float uScan;        // 0..1 VHS scan-lines + tape noise
        uniform float uTime;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
        const mat3 RGB2YIQ = mat3(0.299, 0.596, 0.211,  0.587, -0.274, -0.523,  0.114, -0.322, 0.312);
        const mat3 YIQ2RGB = mat3(1.0, 1.0, 1.0,  0.956, -0.272, -1.106,  0.621, -0.647, 1.703);

        float hash11(float n) {
            return fract(sin(n * 91.3458) * 47453.5453);
        }

        vec3 hueRotate(vec3 c, float a) {
            vec3 yiq = RGB2YIQ * c;
            float cs = cos(a);
            float sn = sin(a);
            float i2 = yiq.y * cs - yiq.z * sn;
            float q2 = yiq.y * sn + yiq.z * cs;
            return YIQ2RGB * vec3(yiq.x, i2, q2);
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec2 p  = (uv - 0.5) / uZoom + 0.5 + uShake;

            if (uGlitch > 0.01) {
                float band = floor(p.y * 28.0);
                float slot = floor(uTime * 14.0);
                float r = hash11(band * 7.13 + slot * 3.71);
                if (r > 1.0 - 0.38 * uGlitch) {
                    p.x += (hash11(band + slot * 5.19) - 0.5) * 0.22 * uGlitch;
                }
                p.y += step(0.96, hash11(slot * 1.37)) * uGlitch * 0.03;
            }

            vec2 dir = vec2(cos(uRgbAngle), sin(uRgbAngle)) * uRgb;
            vec3 c;
            c.r = texture2D(uTexture, p + dir).r;
            c.g = texture2D(uTexture, p).g;
            c.b = texture2D(uTexture, p - dir).b;

            float l = dot(c, LUM);
            c = mix(vec3(l), c, uSat);
            if (abs(uHue) > 0.001) {
                c = hueRotate(c, uHue);
            }

            if (uScan > 0.01) {
                float s = 0.5 + 0.5 * sin(p.y * 700.0 + uTime * 5.0);
                c *= 1.0 - uScan * 0.16 * s;
                c += uScan * 0.03 * hash11(floor(p.y * 240.0) + floor(uTime * 30.0));
            }

            c += vec3(uFlash);

            float d = length(uv - 0.5);
            c *= 1.0 - uBass * 0.22 * smoothstep(0.25, 0.75, d);

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """
}
