# START HERE — what was checked, what changed, how to test it

## 1. In one minute
Your app is a camera app. When I opened it up I found that **most of the clever code was never connected**:

* The 17 "filters" and the Thermal / Cinematic / Portrait modes were written, but the screen showed the plain camera. Nothing you picked changed the picture.
* The audio cleaning only moved the little sound meter. It never touched the sound saved in a video.
* Photos were saved twice (one tiny, one sideways).

**Now:** the camera picture goes through the phone's graphics chip (GPU) first. The look and the new **Pulse** effects are added there.
The same finished picture is then shown on screen, saved into videos, and (for photos) applied again at full size.

## 2. What I audited — every file in the project
(4,786 lines of Kotlin in 23 files, plus build, permissions, CI and sounds.)

| File | What it does | What I found | What I did |
|---|---|---|---|
| `CamixApp.kt` | starts the app | fine | kept |
| `MainActivity.kt` | asks permissions, opens screen | saying "no" to the microphone blocked the whole app | fixed (only camera is required) |
| `ui/theme/*` (3 files) | colours, fonts | fine | kept |
| `ui/screens/CamixCameraScreen.kt` | the screen | used a plain preview (no filters); fake histogram; whole screen redrawn 40×/s by the sound meter; focus ring bugs | rewritten parts, added Pulse panel |
| `ui/viewmodel/CameraViewModel.kt` | the logic behind the screen | black preview after the app is re-created; beeps recorded into videos; Thermal look stuck when going back to Photo; unused DI | rewritten |
| `camera/UltraCameraManager.kt` | controls the camera | no fallback if 4 camera streams are not supported; wrong focus position; photos processed on CPU | rewritten |
| `camera/AutoExposureController.kt` | auto brightness | dark scenes never reached the target; bright scenes flickered up/down | rewritten (tested) |
| `camera/ComputationalPhotoEngine.kt` | "enhance" photos on CPU | made a 1.5 MP sideways copy | deleted, replaced by GPU still renderer |
| `camera/ImageEnhancementPipeline.kt` | more CPU filters | never called anywhere | deleted |
| `camera/MediaStoreSaver.kt` | saves to gallery | not used | rewritten and used |
| `filter/FilterParameters.kt` | list of look settings | "Original" did not switch AI Auto off | rewritten |
| `filter/CameraFilterShaders.kt` | GPU look code | highlights slider backwards; effects overwrote each other; one undefined GPU call | rewritten + 3 new looks |
| `filter/FilterRenderer.kt` | draws filters | **never used** | deleted, replaced by `gl/` |
| `filter/ShaderProgram.kt` | GPU helper | slow lookups | improved |
| `filter/SceneDetector.kt` | AI scene guess | colours were garbage | rewritten, real histogram |
| `audio/AudioProcessor.kt` | microphone | never reached recordings; mic open all the time | rewritten |
| `audio/SoundManager.kt` | UI beeps | impossible injection setup | fixed |
| `di/CameraModule.kt` | DI | dead code | deleted |
| `app/build.gradle.kts`, manifest, theme, CI | build | extra permissions, tests blocked the APK, white flash on launch | fixed |

New code: `gl/` (6 files), `audio/` (BeatDetector, Dsp, Fft), `pulse/` (2), `record/VideoRecorder.kt`, 7 test files.

## 3. How the camera works now (plain English)
1. **CameraX** opens the camera (as before) and sends every frame to a **GPU texture** instead of straight to the screen.
2. **Step A:** the GPU turns the frame upright. (The phone camera sensor is mounted sideways; Android already rotates it for us, I only add what is missing.)
3. **Step B – Look:** colour/tone first, then one style (Portrait, Night, Toon, Neon …).
4. **Step C – Pulse:** the microphone is analysed. On every kick/clap the picture punches in, splits colours, glitches, etc.
5. The finished picture goes to **two places at once**: the screen, and the **video encoder** when you record.
6. **Photos:** the camera takes a normal full-size JPEG, the same look is applied on the GPU at full size, and **one** correct file is saved.

## 4. Deep-research check (official Android docs)
I could not compile or run the app here, so I checked my own new code against the official CameraX docs. It found **two real bugs**, both fixed:

* **Sideways viewfinder.** The docs say that for a SurfaceTexture the matrix already puts the preview in natural orientation, and only the display rotation is left to add. My code added the full sensor rotation on top, which would have turned the picture 90°. Fixed in `gl/CameraGlPipeline.kt` + `gl/GlUtil.kt`, with tests.
* **Tap-to-focus pointed to the wrong spot.** CameraX focus points use the camera's own (sideways) orientation. I now convert your tap to that orientation. Fixed in `camera/UltraCameraManager.kt`, with tests.
* Also confirmed in the docs: `setJpegQuality`, `getSensorRotationDegrees`, `SurfaceOrientedMeteringPointFactory(width, height, useCase)`, and that the muxer accepts a read-write file descriptor.

## 5. What is NOT proven yet (honest list)
* The Kotlin has **not been compiled** and the app has **not run on a phone**. I used offline checks (brace/import/GLSL lint) and Python models of the maths; the 7 unit-test files mirror those.
* Pulse is signal processing, not a neural network. "Portrait" is colour-based soft focus, not a real person cut-out.
* Voice noise-cleaning treats long steady sounds as noise (use the *Music* preset for music).

## 6. Test it in 5 minutes
1. Push the project → GitHub Actions → **Build Debug APK** should turn green. Install it.
2. Open the app: the picture must be **upright** (rotate your phone; the app stays portrait). Switch to the front camera: it must look like a mirror.
3. Tap the screen: the focus ring appears where you tapped and focus follows.
4. Pick **Thermal** or **Neon**: the picture must change **live**.
5. **Photo** mode: take a photo. Check the gallery: one picture, upright, sharp.
6. **Video** mode: record 5 seconds and talk. Play it back: picture + your voice.
7. **Pulse** mode: play music with a strong beat near the phone. The picture should punch with the kick. Record 5 s and play it back: the effects are in the file.

## 7. If something fails — send me this
* **Build fails:** in GitHub open the red step, copy the lines that start with `e:` (and the 3 lines above them).
* **Black screen / wrong rotation / crash:** connect the phone and run
  `adb logcat | grep -E "CameraGlPipeline|UltraCameraManager|VideoRecorder|StillRenderer|AudioProcessor"`
  and copy the last 60 lines. If the GPU part cannot start, the app shows a plain preview and says so on screen.
