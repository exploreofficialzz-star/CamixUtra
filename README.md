# CamixUltra

An Android camera app (Kotlin, Jetpack Compose, CameraX + a custom OpenGL ES pipeline) whose standout feature is
**Pulse**: the camera listens to the microphone and the picture moves with the beat — live, and baked into the video you record.

## Features
* **Live GPU looks** — master colour/tone/detail stage + one style: Portrait, Night, Vivid, Cinematic, Thermal,
  Ultrasound, Focus-peak, Toon, Neon, Dream. The look you see is the look in video and in full-resolution photos.
* **Pulse mode** — beat/onset/tempo detection drives zoom punch, RGB split, glitch, strobe, hue spin and VHS effects.
* **Real audio DSP in recordings** — Natural / Voice (noise suppression + presence) / Music presets.
* **AI Auto** — scene detection (portrait, night, backlit, landscape, …) fades suggested looks in smoothly; smart exposure.
* **Photo / Video / Pulse / Time-lapse (MP4) / Pro**, tap-to-focus, long-press AE/AF lock, pinch zoom, histogram, grid.

## Architecture
```
CameraX Preview ─► gl/CameraGlPipeline ─► [copy/rotate] ─► [look: master+style] ─► [pulse]
                        │                                                         ├─► SurfaceView (viewfinder)
                        │                                                         └─► record/VideoRecorder (H.264 + AAC → MP4)
CameraX ImageCapture ─► gl/StillRenderer (same look, ≤4096 px) ─► gallery
CameraX ImageAnalysis ─► filter/SceneDetector ─► AI-Auto look + camera/AutoExposureController
audio/AudioProcessor (single mic owner) ─► BeatDetector ─► pulse/PulseEngine ─► shaders
                                         └► DSP chain ─► meter + AudioSink ─► AAC track
```
If the GPU path cannot start on a device, the UI falls back to a plain CameraX preview (photos still work).
New here? Read `START_HERE.md` (plain English). Technical detail: `AUDIT.md`.

## Build
```
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug
./gradlew test               # JVM unit tests (DSP, beat detection, exposure, geometry)
```
`gradle-wrapper.jar` is not committed; CI downloads it (see `.github/workflows/android.yml`).
Requires JDK 17, Android SDK 34, minSdk 26.
