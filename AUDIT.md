# CamixUltra — audit & fix report

> **Verification limits (read first).** This audit was done without an Android SDK, emulator or Kotlin compiler.
> The risky maths was prototyped and measured in Python first (scripts in `proto/`, results quoted below) and
> then ported 1:1 to Kotlin; JVM unit tests that mirror those prototypes are in `app/src/test`. Everything else
> (Kotlin compilation, GL behaviour, A/V sync on real hardware) is **statically reviewed but not executed**.
> First things to check on a phone: ① viewfinder appears and is upright on both cameras, ② a Video/Pulse clip plays
> with sound, ③ Pulse reacts to a kick drum / clap. If CI prints `e:` lines, paste them and they are quick fixes.

## 1. Why the app felt "dead"

| # | Severity | Finding (evidence) | Fix |
|---|----------|--------------------|-----|
| 1 | **Critical** | **The whole OpenGL filter engine was never connected.** The viewfinder was a CameraX `PreviewView`; `FilterRenderer` was never instantiated, `setFilterRenderer()` never called, there is no `GLSurfaceView` anywhere. No preset, style or slider ever changed the preview, a photo or a video — Thermal/Cinematic/Portrait/Night looked like the plain camera. | New GPU pipeline (`gl/`): camera → upright frame → look (master + style) → Pulse composite → viewfinder **and** video encoder. |
| 2 | **Critical** | **Audio DSP never reached a recording.** `AudioProcessor` processed a *copy* for the meter while CameraX recorded the raw mic; two concurrent `AudioRecord`s; the mic stayed open for the whole app lifetime (battery, privacy indicator, even in background). | One `AudioProcessor` owns the mic; its processed stream is what the recorder encodes. Mic opens only in Video/Pulse/visualizer and only while the app is visible. |
| 3 | **High** | **Photos:** the "enhanced" image was a *second* file down-scaled to ≤1536 px (~1.5 MP) and saved without EXIF rotation (portrait shots appeared sideways), next to the untouched original. Time-lapse ran this CPU engine per frame. | Photo = sensor JPEG → same GPU look at ≤4096 px → **one** correctly oriented file (or the untouched JPEG for "Original"). Time-lapse now produces one MP4. |
| 4 | **High** | **Scene detector read garbage colours:** it handed `YuvImage` only the luma plane labelled NV21, then JPEG-compressed and decoded it every tick. Smoothing was initialised at 0.5 on a 0–255 scale. | Direct stride-aware YUV_420_888 sampling (~12 k points, no allocations), smoothing seeded from the first frame, robust (0.5–99.5 %) contrast. |
| 5 | **High** | **Shader semantic bugs:** highlights slider inverted (negative = *added* light, so BACKLIT "recovery" blew highlights); dehaze and clarity re-sampled the texture and silently discarded sharpen/dehaze; vignette used `smoothstep` with `edge0 > edge1` (undefined in GLSL ES); cinematic letterbox worked in sensor space (wrong axis once the frame is rotated); Portrait preset had `blur = 0` so its shader was unreachable (same for Night/Vivid). | Rewritten shader library; every look runs on the upright frame; presets select their style explicitly. GLSL lint: 0 findings, all 12 programs' uniforms are set. |
| 6 | **High** | **"Original" did not turn AI Auto off** (the default `FilterParameters` has `isAutoEnabled = true`); manual slider edits were applied to a stale copy and overwritten by AI Auto twice a second. | Every non-Auto preset sets `isAutoEnabled = false`; sliders start from the look on screen. |
| 7 | **High** | **Auto-exposure:** absolute P-controller treating EV *index* as stops. Simulation (camera-AE lag model): dark scene (brightness 40) settled at **55** instead of ~130; bright scene (200) **hunted, 14 index flips in the 2nd half**; manual EV was never clamped to the device range. | Integral controller in EV units with dead-band, 1-index slew limit, real `exposureCompensationStep`, ±2 EV clamp. Same simulation: 0 flips for scenes 70–240, converges to 123–135. |
| 8 | Medium | Tap-to-focus used view pixels with a factory that assumes the whole sensor frame while the preview is centre-cropped → focus landed off-target; the focus ring hid early after repeated taps; tapping while locked moved the ring without refocusing. | Tap is mapped through the exact crop/mirror (verified numerically) into the camera frame; tap while locked unlocks. |
| 9 | Medium | **ViewModel:** `cameraStarted` guard ⇒ black preview after activity re-creation; start/stop beeps were recorded into every video; leaving Thermal/Cinematic/Portrait/Night kept that look in Photo; Hilt `CameraModule` provided singletons nobody injected (and `SoundManager` had an unresolvable `@Inject constructor(Context)`). | Re-bind on every owner change; beep first, record after; mode→look state machine; dead DI removed. |
| 10 | Medium | The whole screen recomposed ~40×/s from the microphone flows. | Audio flows are collected inside their own composables. |
| 11 | Medium | Four simultaneous use cases (Preview + ImageCapture + VideoCapture + ImageAnalysis) fail on many LIMITED-level devices; no fallback. | Now Preview + ImageCapture + ImageAnalysis (video is encoded from the GPU frame) with automatic retry without analysis. |
| 12 | Medium | Manifest requested `READ_MEDIA_IMAGES/VIDEO`, `READ_EXTERNAL_STORAGE`, `FLASHLIGHT`, `VIBRATE`, `MODIFY_AUDIO_SETTINGS` (none used; Play photo/video-policy risk); microphone `required="true"`; denying the microphone blocked the whole app. | Removed; microphone optional; only the camera is mandatory. |
| 13 | Low | Histogram overlay drew a fixed bell curve. | Real RGB histogram from the analysed frames. |
| 14 | Low | Release/CI: unit-test failures blocked the debug APK; light base theme flashed white at launch; README claimed 17 shaders (there were ~10 programs). | Debug build independent of tests; dark theme; README rewritten. |

Removed (dead or harmful): `FilterRenderer`, `ImageEnhancementPipeline` (never called), `ComputationalPhotoEngine` (item 3),
`di/CameraModule`, CameraX `camera-video` / `camera-extensions` dependencies.

## 2. New: **Pulse** — the camera listens

Techniques borrowed from AI-music-video / short-form editors, running live and *baked into the recording*:

* **Beat & onset detection** (spectral flux, 3 bands, adaptive thresholds, level gate) → kick / hit events + BPM.
  Prototype measurements on synthetic drum loops: kick precision/recall **1.00 / 1.00 for 90–160 BPM**, **0** false
  triggers on white noise at three levels, tempo within 1.5 % for 75–140 BPM (150 BPM resolves to its half-time, 75).
* **Audio-reactive GPU effects:** zoom punch, RGB split, glitch blocks, strobe, hue spin, VHS tape, bass-driven
  vignette — seven styles + intensity slider (`pulse/`, `PULSE` shader).
* **Stylised "AI looks"** usable alone or with Pulse: Toon, Neon, Dream (bloom), plus Portrait / Night / Vivid /
  Cinematic / Thermal / Ultrasound / Focus-peak.
* **Real recorded-audio DSP** (preset *Natural / Voice / Music*): rumble high-pass, **spectral noise suppression**
  (minimum-statistics noise tracking; +5 dB SNR in simulation at 0–20 dB input SNR), 3 kHz presence EQ, soft limiter.
  Beat analysis taps the raw mic so the DSP never starves the visuals.
* **Time-lapse → MP4** (one frame pushed per interval, plays at 30 fps).

Limits: Pulse is DSP, not a neural network. Voice noise suppression treats *stationary* sounds (held notes) as noise
— use *Music* for music. Visuals lag the sound by roughly 80–120 ms. Portrait is colour/skin heuristics, not segmentation.

## 3. Tests (`./gradlew test`)
FFT vs naive DFT · biquad response & block continuity · limiter bound · denoiser attenuation · beat/tempo on a
synthetic loop and on noise · Pulse envelopes · exposure controller closed-loop simulation · rotation / crop matrices ·
parameter blending. Each mirrors a Python reference run during this audit.

## 4. Suggested next steps
Person segmentation (ML Kit) for true background blur/replace; auto-captions (on-device speech recognition) burned into
Pulse clips; beat-grid-aware export on a saved clip; stabilisation.
