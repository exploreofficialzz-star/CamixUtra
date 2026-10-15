package com.chastechgroup.camix.camera

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.SceneDetector
import com.chastechgroup.camix.filter.SceneType
import com.chastechgroup.camix.gl.CameraGlPipeline
import com.chastechgroup.camix.gl.GlMath
import com.chastechgroup.camix.gl.StillRenderer
import com.chastechgroup.camix.pulse.PulseEngine
import com.chastechgroup.camix.record.VideoRecorder
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Orchestrates everything that touches the camera:
 *
 *  CameraX (Preview + ImageCapture + ImageAnalysis)
 *     Preview ───► CameraGlPipeline (look + Pulse FX) ───► viewfinder  +  VideoRecorder (MP4)
 *     ImageCapture ► StillRenderer (same look, full resolution) ► gallery
 *     ImageAnalysis ► SceneDetector ► AI-Auto look + AutoExposureController
 *  AudioProcessor ─► PulseEngine (visuals)  and  VideoRecorder (processed audio track)
 *
 * If the GPU path cannot start on a device, [glFailed] flips and the UI supplies a plain CameraX
 * PreviewView provider instead (photos still work; live FX and recording are disabled).
 */
class UltraCameraManager(
    private val context: Context,
    val audio: AudioProcessor,
    val pulse: PulseEngine
) : CameraGlPipeline.Listener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    val glPipeline = CameraGlPipeline(this)
    val sceneDetector = SceneDetector()
    val autoExposure = AutoExposureController()
    private val stillRenderer = StillRenderer()

    // ── CameraX objects ───────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var boundOwner: LifecycleOwner? = null
    private var fallbackProvider: Preview.SurfaceProvider? = null
    private var analysisAvailable = true

    // ── State ─────────────────────────────────────────────────────────────────
    private val _cameraState = MutableStateFlow(CamState.IDLE)
    val cameraState: StateFlow<CamState> = _cameraState.asStateFlow()

    private val _detectedScene = MutableStateFlow(SceneType.AUTO)
    val detectedScene: StateFlow<SceneType> = _detectedScene.asStateFlow()

    private val _filterParams = MutableStateFlow(FilterParameters.DEFAULT)
    val filterParams: StateFlow<FilterParameters> = _filterParams.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _aeAfLocked = MutableStateFlow(false)
    val aeAfLocked: StateFlow<Boolean> = _aeAfLocked.asStateFlow()

    private val _isTimelapse = MutableStateFlow(false)
    val isTimelapse: StateFlow<Boolean> = _isTimelapse.asStateFlow()

    private val _timelapseCaptured = MutableStateFlow(0)
    val timelapseCaptured: StateFlow<Int> = _timelapseCaptured.asStateFlow()

    private val _exposureLabel = MutableStateFlow("Auto")
    val exposureLabel: StateFlow<String> = _exposureLabel.asStateFlow()

    private val _isClipping = MutableStateFlow(false)
    val isClipping: StateFlow<Boolean> = _isClipping.asStateFlow()

    private val _processingStatus = MutableStateFlow("")
    val processingStatus: StateFlow<String> = _processingStatus.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _glFailed = MutableStateFlow(false)
    /** true => the GPU path is unavailable; the UI must supply a plain PreviewView provider. */
    val glFailed: StateFlow<Boolean> = _glFailed.asStateFlow()

    private val _glMessage = MutableStateFlow<String?>(null)
    val glMessage: StateFlow<String?> = _glMessage.asStateFlow()

    private val _isFront = MutableStateFlow(false)
    val isFront: StateFlow<Boolean> = _isFront.asStateFlow()

    private var isAutoEnhanceEnabled = true
    private var recorder: VideoRecorder? = null
    private var timelapseRecorder: VideoRecorder? = null
    private var timelapseJob: Job? = null
    private var timerJob: Job? = null

    init {
        pulse.enabled = false
        glPipeline.pulseSource = pulse
        audio.featureListener = { f -> pulse.onFeatures(f) }

        scope.launch {
            sceneDetector.suggestedParams.collect { params ->
                if (isAutoEnhanceEnabled) applyLook(params)
            }
        }
        scope.launch { sceneDetector.detectedScene.collect { _detectedScene.value = it } }
        scope.launch { autoExposure.exposureLabel.collect { _exposureLabel.value = it } }
        scope.launch { autoExposure.isClipping.collect { _isClipping.value = it } }
        scope.launch {
            sceneDetector.sceneStats.collect { stats ->
                if (stats != null) {
                    val scene = _detectedScene.value
                    autoExposure.updateScene(
                        brightness = stats.avgBrightness,
                        darkRatio = stats.darkRatio,
                        brightRatio = stats.brightRatio,
                        skinRatio = stats.skinRatio,
                        hfEnergy = stats.hfEnergyRatio,
                        isNight = scene == SceneType.NIGHT || scene == SceneType.LOW_LIGHT,
                        isBacklit = scene == SceneType.BACKLIT
                    )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GPU pipeline callbacks (posted to the main thread by the pipeline)
    // ══════════════════════════════════════════════════════════════════════════

    override fun onGlFatal(message: String) {
        Timber.e("GPU pipeline unavailable: %s", message)
        if (_isRecording.value) stopRecording({ }, { })
        if (_isTimelapse.value) stopTimelapse({ }, { })
        _glMessage.value = message
        _glFailed.value = true
        // rebind with the plain preview as soon as the UI provides a provider
        val owner = boundOwner
        if (owner != null && fallbackProvider != null) bind(owner, null)
    }

    override fun onFirstFrame() {
        Timber.d("First GPU frame rendered")
    }

    /** Camera -> GPU pipeline. */
    private val glSurfaceProvider = Preview.SurfaceProvider { request ->
        val res = request.resolution
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        request.setTransformationInfoListener(mainExecutor) { info ->
            // the camera orientation is already inside SurfaceTexture.getTransformMatrix(); we only add what is left
            val sensor = camera?.cameraInfo?.sensorRotationDegrees ?: info.rotationDegrees
            glPipeline.updateTransform(info.rotationDegrees, sensor, front)
        }
        glPipeline.createCameraSurface(res.width, res.height) { surface ->
            if (surface == null) {
                request.willNotProvideSurface()
            } else {
                request.provideSurface(surface, mainExecutor) { _ -> glPipeline.releaseCameraSurface(surface) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Camera lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * @param fallback plain-preview provider (PreviewView), used only when the GPU path failed
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        fallback: Preview.SurfaceProvider?,
        onError: (String) -> Unit
    ) {
        boundOwner = lifecycleOwner
        fallbackProvider = fallback
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bind(lifecycleOwner, onError)
            } catch (e: Exception) {
                Timber.e(e, "Camera provider failed")
                _cameraState.value = CamState.ERROR
                onError("Camera init failed: " + e.message)
            }
        }, mainExecutor)
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, onError: (String) -> Unit) {
        if (_isRecording.value || _isTimelapse.value) return
        val provider = cameraProvider ?: return
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(target).build()
        if (!provider.hasCamera(selector)) {
            onError("No ${if (target == CameraSelector.LENS_FACING_FRONT) "front" else "back"} camera found")
            return
        }
        lensFacing = target
        _isFront.value = target == CameraSelector.LENS_FACING_FRONT
        _zoomLevel.value = 1f
        boundOwner = lifecycleOwner
        bind(lifecycleOwner, onError)
    }

    private fun bind(owner: LifecycleOwner, onError: ((String) -> Unit)?) {
        val provider = cameraProvider ?: return
        val surfaceProvider: Preview.SurfaceProvider? =
            if (_glFailed.value) fallbackProvider else glSurfaceProvider
        if (surfaceProvider == null) {
            Timber.w("bind(): waiting for a fallback preview provider")
            return
        }

        autoExposure.detach()
        provider.unbindAll()
        camera = null

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
        pv.setSurfaceProvider(mainExecutor, surfaceProvider)

        val ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setFlashMode(_flashMode.value.toCX())
            .setJpegQuality(95)
            .build()

        var ia: ImageAnalysis? = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        ia?.setAnalyzer(analysisExecutor) { proxy -> sceneDetector.processFrame(proxy) }

        try {
            val cam = try {
                provider.bindToLifecycle(owner, selector, pv, ic, ia!!)
            } catch (e: Exception) {
                // 3 simultaneous streams are not supported on every device: drop scene analysis, keep the rest
                Timber.w(e, "Preview+Capture+Analysis not supported - retrying without analysis")
                ia?.clearAnalyzer()
                ia = null
                analysisAvailable = false
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, pv, ic)
            }
            sceneDetector.enabled = ia != null
            camera = cam
            preview = pv
            imageCapture = ic
            autoExposure.attach(cam)
            _cameraState.value = CamState.READY
            _isFront.value = lensFacing == CameraSelector.LENS_FACING_FRONT
            applyTorch()
            Timber.d("Camera bound (lens=%d, gl=%b, analysis=%b)", lensFacing, !_glFailed.value, ia != null)
        } catch (e: Exception) {
            Timber.e(e, "bindToLifecycle failed")
            _cameraState.value = CamState.ERROR
            onError?.invoke("Camera bind failed: " + e.message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Zoom / focus / exposure / flash
    // ══════════════════════════════════════════════════════════════════════════

    fun setZoom(zoom: Float) {
        val z = zoom.coerceIn(1f, getMaxZoom())
        _zoomLevel.value = z
        camera?.cameraControl?.setZoomRatio(z)
    }

    fun pinchZoom(scale: Float) = setZoom(_zoomLevel.value * scale)

    fun getMaxZoom(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    /** Map a tap on the viewfinder (0..1, top-left origin) to the camera frame and build the metering action. */
    private fun meteringAction(nx: Float, ny: Float, locked: Boolean): FocusMeteringAction? {
        val pv = preview ?: return null
        val sx = glPipeline.cropScaleX
        val sy = glPipeline.cropScaleY
        var cx = 0.5f + (nx - 0.5f) / sx
        val cy = 0.5f + (ny - 0.5f) / sy
        // the viewfinder is mirrored for the selfie camera, the camera frame is not
        if (!_glFailed.value && glPipeline.mirrorDisplay) cx = 1f - cx
        // CameraX metering points live in the camera's own (sensor / buffer) orientation, not in the upright frame
        val sensor = camera?.cameraInfo?.sensorRotationDegrees ?: 90
        val remaining = if (_glFailed.value) 0 else glPipeline.remainingRotation
        val b = GlMath.frameToBuffer(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), remaining, sensor)
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f, pv)
        val point = factory.createPoint(b[0].coerceIn(0f, 1f), b[1].coerceIn(0f, 1f), 0.12f)
        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (locked) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        return builder.build()
    }

    fun tapToFocus(nx: Float, ny: Float) {
        if (_aeAfLocked.value) return
        val action = meteringAction(nx, ny, false) ?: return
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun lockAeAf(nx: Float, ny: Float) {
        val action = meteringAction(nx, ny, true) ?: return
        camera?.cameraControl?.startFocusAndMetering(action)
        _aeAfLocked.value = true
    }

    fun unlockAeAf() {
        camera?.cameraControl?.cancelFocusAndMetering()
        _aeAfLocked.value = false
        autoExposure.enableAuto()
    }

    fun setExposureCompensation(index: Int) = autoExposure.setManualEv(index)

    fun toggleFlash() {
        val modes = FlashMode.entries.toTypedArray()
        _flashMode.value = modes[(modes.indexOf(_flashMode.value) + 1) % modes.size]
        imageCapture?.flashMode = _flashMode.value.toCX()
        applyTorch()
    }

    /** Flash "ON" doubles as a torch while a video is recording. */
    private fun applyTorch() {
        val on = _flashMode.value == FlashMode.ON && (_isRecording.value || _isTimelapse.value)
        try { camera?.cameraControl?.enableTorch(on) } catch (_: Exception) { }
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() ?: false

    // ══════════════════════════════════════════════════════════════════════════
    // Look
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyLook(params: FilterParameters) {
        _filterParams.value = params
        glPipeline.setLook(params)
    }

    fun updateFilterParameters(params: FilterParameters) {
        isAutoEnhanceEnabled = params.isAutoEnabled
        applyLook(params)
    }

    fun setAutoEnhance(enabled: Boolean) {
        isAutoEnhanceEnabled = enabled
        if (enabled) {
            autoExposure.enableAuto()
            applyLook(sceneDetector.suggestedParams.value)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Photo
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Full-resolution JPEG from the sensor, then the viewfinder's look is applied on the GPU at (up to)
     * 4096 px and ONE correctly-oriented file is saved. With the "Original" look the camera's JPEG is saved untouched.
     */
    fun capturePhoto(
        onProgress: (String) -> Unit,
        onDone: (String) -> Unit,
        onFail: (String) -> Unit
    ) {
        val ic = imageCapture
        if (ic == null) { onFail("Camera not ready"); return }
        if (_isProcessing.value) return

        val raw = File(context.cacheDir, "raw_" + System.currentTimeMillis() + ".jpg")
        _cameraState.value = CamState.CAPTURING
        _isProcessing.value = true
        _processingStatus.value = "Capturing…"

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processAndSave(raw, onProgress, onDone, onFail)
                }

                override fun onError(exception: ImageCaptureException) {
                    raw.delete()
                    _isProcessing.value = false
                    _processingStatus.value = ""
                    _cameraState.value = CamState.READY
                    onFail("Capture failed: " + exception.message)
                }
            })
    }

    private fun processAndSave(
        raw: File,
        onProgress: (String) -> Unit,
        onDone: (String) -> Unit,
        onFail: (String) -> Unit
    ) {
        val params = _filterParams.value
        scope.launch(Dispatchers.IO) {
            var uri: Uri? = null
            var withLook = false
            val name = "CamixUltra_" + System.currentTimeMillis()
            try {
                if (!params.isNeutral()) {
                    postProgress("Applying look…", onProgress)
                    val bmp = stillRenderer.render(raw, params)
                    if (bmp != null) {
                        postProgress("Saving…", onProgress)
                        uri = MediaStoreSaver.savePhotoBitmap(context, bmp, name, 95)
                        withLook = uri != null
                        bmp.recycle()
                    }
                }
                if (uri == null) {
                    postProgress("Saving…", onProgress)
                    uri = MediaStoreSaver.savePhotoFile(context, raw, name)
                }
            } catch (t: Throwable) {
                Timber.e(t, "processAndSave")
            } finally {
                raw.delete()
            }
            withContext(Dispatchers.Main) {
                _isProcessing.value = false
                _processingStatus.value = ""
                _cameraState.value = CamState.READY
                if (uri != null) onDone(if (withLook) "Photo saved with your look" else "Photo saved")
                else onFail("Could not save the photo")
            }
        }
    }

    private suspend fun postProgress(text: String, cb: (String) -> Unit) {
        withContext(Dispatchers.Main) {
            _processingStatus.value = text
            cb(text)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Video (look + Pulse baked in, processed audio)
    // ══════════════════════════════════════════════════════════════════════════

    fun startRecording(
        withAudio: Boolean,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isRecording.value || _isTimelapse.value) return
        if (_glFailed.value) { onError("Live effects are unavailable on this device, so video recording is disabled"); return }
        val w = glPipeline.contentWidth
        val h = glPipeline.contentHeight
        if (w <= 0 || h <= 0) { onError("Viewfinder not ready yet"); return }

        val rec = VideoRecorder(context)
        try {
            val useAudio = withAudio && audio.isRunning.value
            val surface = rec.prepare(w, h, useAudio, false)
            val ok = glPipeline.startEncoder(surface, rec.width, rec.height, false) { ts -> rec.videoPtsNs(ts) }
            if (!ok) {
                rec.abort()
                onError("Could not start the video encoder")
                return
            }
            rec.start()
            if (rec.isWithAudio) audio.sink = rec
            recorder = rec
            _isRecording.value = true
            _cameraState.value = CamState.RECORDING
            startTimer()
            applyTorch()
            onStarted()
        } catch (t: Throwable) {
            Timber.e(t, "startRecording failed")
            glPipeline.stopEncoder()
            rec.abort()
            onError("Recording failed: " + t.message)
        }
    }

    fun stopRecording(onFinished: (String) -> Unit, onError: (String) -> Unit) {
        val rec = recorder ?: return
        recorder = null
        audio.sink = null
        glPipeline.stopEncoder()
        _isRecording.value = false
        _cameraState.value = CamState.READY
        stopTimer()
        applyTorch()
        finishRecorder(rec, "Video saved to Movies/CamixUltra", onFinished, onError)
    }

    private fun finishRecorder(
        rec: VideoRecorder, okText: String,
        onFinished: (String) -> Unit, onError: (String) -> Unit
    ) {
        _isProcessing.value = true
        _processingStatus.value = "Finishing video…"
        rec.stop { uri, error ->
            scope.launch(Dispatchers.Main) {
                _isProcessing.value = false
                _processingStatus.value = ""
                if (uri != null) onFinished(okText) else onError(error ?: "Recording failed")
            }
        }
    }

    private fun startTimer() {
        _recordingSeconds.value = 0
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _recordingSeconds.value = _recordingSeconds.value + 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _recordingSeconds.value = 0
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Time-lapse -> a real video (the old version saved hundreds of JPEGs, each run through the CPU engine)
    // ══════════════════════════════════════════════════════════════════════════

    fun startTimelapse(
        intervalMs: Long,
        onStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isTimelapse.value || _isRecording.value) return
        if (_glFailed.value) { onError("Live effects are unavailable on this device, so time-lapse is disabled"); return }
        val w = glPipeline.contentWidth
        val h = glPipeline.contentHeight
        if (w <= 0 || h <= 0) { onError("Viewfinder not ready yet"); return }

        val rec = VideoRecorder(context)
        try {
            val surface = rec.prepare(w, h, false, true)
            val ok = glPipeline.startEncoder(surface, rec.width, rec.height, true) { _ -> -1L }
            if (!ok) {
                rec.abort()
                onError("Could not start the video encoder")
                return
            }
            rec.start()
            timelapseRecorder = rec
            _isTimelapse.value = true
            _timelapseCaptured.value = 0
            _cameraState.value = CamState.RECORDING
            applyTorch()
            timelapseJob = scope.launch {
                var i = 0L
                while (isActive) {
                    glPipeline.pushTimelapseFrame(i * 33_333_333L)     // plays back at 30 fps
                    i++
                    _timelapseCaptured.value = i.toInt()
                    delay(intervalMs)
                }
            }
            onStarted()
        } catch (t: Throwable) {
            Timber.e(t, "startTimelapse failed")
            glPipeline.stopEncoder()
            rec.abort()
            onError("Time-lapse failed: " + t.message)
        }
    }

    fun stopTimelapse(onFinished: (String) -> Unit, onError: (String) -> Unit) {
        timelapseJob?.cancel()
        timelapseJob = null
        val rec = timelapseRecorder ?: return
        timelapseRecorder = null
        glPipeline.stopEncoder()
        _isTimelapse.value = false
        _cameraState.value = CamState.READY
        applyTorch()
        finishRecorder(rec, "Time-lapse video saved (" + _timelapseCaptured.value + " frames)", onFinished, onError)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Release
    // ══════════════════════════════════════════════════════════════════════════

    fun release() {
        timelapseJob?.cancel()
        timerJob?.cancel()
        audio.sink = null
        recorder?.abort(); recorder = null
        timelapseRecorder?.abort(); timelapseRecorder = null
        autoExposure.detach(); autoExposure.release()
        sceneDetector.release()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        glPipeline.release()
        analysisExecutor.shutdown()
        scope.cancel()
    }

    // ── Enums ─────────────────────────────────────────────────────────────────
    enum class CamState { IDLE, READY, CAPTURING, RECORDING, PAUSED, ERROR }

    enum class FlashMode {
        OFF, ON, AUTO;
        fun toCX() = when (this) {
            OFF  -> ImageCapture.FLASH_MODE_OFF
            ON   -> ImageCapture.FLASH_MODE_ON
            AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }
}
