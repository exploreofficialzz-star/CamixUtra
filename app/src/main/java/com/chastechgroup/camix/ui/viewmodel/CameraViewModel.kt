package com.chastechgroup.camix.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.chastechgroup.camix.audio.AudioPreset
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.audio.SoundManager
import com.chastechgroup.camix.camera.UltraCameraManager
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.FilterPreset
import com.chastechgroup.camix.pulse.PulseEngine
import com.chastechgroup.camix.pulse.PulseStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    // The managers are owned by this ViewModel (they are released in onCleared()).
    // The old Hilt CameraModule provided singletons nobody injected, so it was removed.
    val audioProcessor = AudioProcessor(ctx)
    val pulseEngine    = PulseEngine()
    val soundManager   = SoundManager(ctx)
    val cameraManager  = UltraCameraManager(ctx, audioProcessor, pulseEngine)

    // ── Flows from managers ───────────────────────────────────────────────────
    val cameraState       = cameraManager.cameraState
    val detectedScene     = cameraManager.detectedScene
    val filterParams      = cameraManager.filterParams
    val isRecording       = cameraManager.isRecording
    val recordingSeconds  = cameraManager.recordingSeconds
    val flashMode         = cameraManager.flashMode
    val zoomLevel         = cameraManager.zoomLevel
    val aeAfLocked        = cameraManager.aeAfLocked
    val isTimelapse       = cameraManager.isTimelapse
    val timelapseCaptured = cameraManager.timelapseCaptured
    val exposureLabel     = cameraManager.exposureLabel
    val isClipping        = cameraManager.isClipping
    val isProcessing      = cameraManager.isProcessing
    val processingStatus  = cameraManager.processingStatus
    val evIndex           = cameraManager.autoExposure.evIndex
    val glFailed          = cameraManager.glFailed
    val glMessage         = cameraManager.glMessage
    val isFront           = cameraManager.isFront
    val histogram         = cameraManager.sceneDetector.histogram

    val rmsLevel     = audioProcessor.rmsLevel
    val waveform     = audioProcessor.waveform
    val noiseFloorDb = audioProcessor.noiseFloorDb
    val bpm          = audioProcessor.bpm
    val kickCount    = audioProcessor.kickCount
    val audioRunning = audioProcessor.isRunning

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _currentMode         = MutableStateFlow(CaptureMode.PHOTO)
    val currentMode: StateFlow<CaptureMode> = _currentMode.asStateFlow()

    private val _currentFilter       = MutableStateFlow(FilterPreset.AUTO)
    val currentFilter: StateFlow<FilterPreset> = _currentFilter.asStateFlow()

    private val _isFilterPanelOpen   = MutableStateFlow(false)
    val isFilterPanelOpen: StateFlow<Boolean> = _isFilterPanelOpen.asStateFlow()

    private val _isAdjustmentOpen    = MutableStateFlow(false)
    val isAdjustmentPanelOpen: StateFlow<Boolean> = _isAdjustmentOpen.asStateFlow()

    private val _isPulsePanelOpen    = MutableStateFlow(false)
    val isPulsePanelOpen: StateFlow<Boolean> = _isPulsePanelOpen.asStateFlow()

    private val _isAutoEnhance       = MutableStateFlow(true)
    val isAutoEnhanceEnabled: StateFlow<Boolean> = _isAutoEnhance.asStateFlow()

    private val _isGridEnabled       = MutableStateFlow(false)
    val isGridEnabled: StateFlow<Boolean> = _isGridEnabled.asStateFlow()

    private val _isHistogramEnabled  = MutableStateFlow(false)
    val isHistogramEnabled: StateFlow<Boolean> = _isHistogramEnabled.asStateFlow()

    private val _isAudioVisualizerOn = MutableStateFlow(false)
    val isAudioVisualizerOn: StateFlow<Boolean> = _isAudioVisualizerOn.asStateFlow()

    private val _manualParams        = MutableStateFlow(FilterParameters.DEFAULT)
    val manualParams: StateFlow<FilterParameters> = _manualParams.asStateFlow()

    private val _timelapseInterval   = MutableStateFlow(2000L)
    val timelapseInterval: StateFlow<Long> = _timelapseInterval.asStateFlow()

    private val _message             = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _proExposureIndex    = MutableStateFlow(0)
    val proExposureIndex: StateFlow<Int> = _proExposureIndex.asStateFlow()

    private val _pulseStyle          = MutableStateFlow(PulseStyle.FUSION)
    val pulseStyle: StateFlow<PulseStyle> = _pulseStyle.asStateFlow()

    private val _pulseIntensity      = MutableStateFlow(0.8f)
    val pulseIntensity: StateFlow<Float> = _pulseIntensity.asStateFlow()

    private val _audioPreset         = MutableStateFlow(AudioPreset.NATURAL)
    val audioPreset: StateFlow<AudioPreset> = _audioPreset.asStateFlow()

    /** true while a special mode (Portrait / Night / Cinematic / Thermal) owns the current look */
    private var modeLookActive = false
    private var foreground = false
    private var startingVideo = false

    // ── Lifecycle (the microphone is only open while it is needed AND the app is visible) ─────────

    fun onAppForeground() {
        foreground = true
        refreshAudio()
    }

    fun onAppBackground() {
        foreground = false
        if (isRecording.value) cameraManager.stopRecording({ }, { })
        if (isTimelapse.value) cameraManager.stopTimelapse({ }, { })
        audioProcessor.stop()
    }

    private fun audioNeeded(): Boolean {
        val m = _currentMode.value
        return m == CaptureMode.VIDEO || m == CaptureMode.PULSE || _isAudioVisualizerOn.value
    }

    private fun refreshAudio() {
        if (foreground && audioNeeded()) {
            val ok = audioProcessor.start()
            if (!ok && _currentMode.value == CaptureMode.PULSE) {
                _message.value = "Pulse listens to the microphone - allow the microphone permission in Settings"
            }
        } else if (!isRecording.value) {
            audioProcessor.stop()
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    /**
     * Safe to call again (activity re-creation, GPU fall-back). [fallback] is only used when the GPU pipeline
     * is unavailable; pass null otherwise.
     */
    fun startCamera(owner: LifecycleOwner, fallback: Preview.SurfaceProvider?) {
        cameraManager.startCamera(owner, fallback) { msg -> _message.value = msg }
    }

    fun switchCamera(owner: LifecycleOwner) {
        soundManager.playModeSwitch()
        cameraManager.switchCamera(owner) { msg -> _message.value = msg }
    }

    /** Called from the SurfaceView callbacks. Pass null when the surface is destroyed. */
    fun attachDisplay(surface: Surface?, width: Int, height: Int) {
        cameraManager.glPipeline.setDisplay(surface, width, height)
    }

    private fun recordingNow() = isRecording.value || isTimelapse.value

    fun toggleFlash() {
        if (!recordingNow()) soundManager.playModeSwitch()      // UI beeps would be captured by the microphone
        cameraManager.toggleFlash()
    }

    fun setZoom(zoom: Float)    = cameraManager.setZoom(zoom)
    fun pinchZoom(scale: Float) = cameraManager.pinchZoom(scale)

    /** (nx, ny) = tap position in the viewfinder, 0..1, origin top-left */
    fun tapToFocus(nx: Float, ny: Float) {
        if (cameraManager.aeAfLocked.value) { unlockAeAf(); return }
        if (!recordingNow()) soundManager.playFocusLock()
        cameraManager.tapToFocus(nx, ny)
    }

    fun lockAeAf(nx: Float, ny: Float) {
        if (!recordingNow()) soundManager.playAeAfLock()
        cameraManager.lockAeAf(nx, ny)
    }

    fun unlockAeAf() = cameraManager.unlockAeAf()

    fun setProExposure(index: Int) {
        _proExposureIndex.value = index
        cameraManager.setExposureCompensation(index)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    fun capturePhoto() {
        soundManager.playShutter()
        cameraManager.capturePhoto(
            onProgress = { },
            onDone = { text -> soundManager.playPhotoSaved(); _message.value = text },
            onFail = { text -> _message.value = text }
        )
    }

    /** Video / Pulse: start or stop. The start beep plays first and recording begins after it, so it is not on the tape. */
    fun toggleRecording() {
        if (isRecording.value) {
            cameraManager.stopRecording(
                onFinished = { text -> soundManager.playVideoStop(); _message.value = text },
                onError = { text -> _message.value = text }
            )
            refreshAudio()
            return
        }
        if (startingVideo) return
        startingVideo = true
        soundManager.playVideoStart()
        viewModelScope.launch {
            delay(240)                               // the start beep is 0.17 s long
            val micOk = audioProcessor.start()       // no-op when already running
            if (!micOk) _message.value = "Microphone unavailable - recording without sound"
            cameraManager.startRecording(
                withAudio = true,
                onStarted = { },
                onError = { text -> _message.value = text }
            )
            startingVideo = false
        }
    }

    fun startTimelapse() {
        if (startingVideo) return
        startingVideo = true
        soundManager.playVideoStart()
        viewModelScope.launch {
            delay(240)
            cameraManager.startTimelapse(
                intervalMs = _timelapseInterval.value,
                onStarted = { },
                onError = { text -> _message.value = text }
            )
            startingVideo = false
        }
    }

    fun stopTimelapse() {
        cameraManager.stopTimelapse(
            onFinished = { text -> soundManager.playVideoStop(); _message.value = text },
            onError = { text -> _message.value = text }
        )
    }

    fun setTimelapseInterval(ms: Long) { _timelapseInterval.value = ms }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun applyFilterPreset(preset: FilterPreset) = applyPreset(preset, fromMode = false)

    private fun applyPreset(preset: FilterPreset, fromMode: Boolean) {
        _currentFilter.value = preset
        val params = preset.toParameters()
        _manualParams.value = params
        _isAutoEnhance.value = preset == FilterPreset.AUTO
        cameraManager.updateFilterParameters(params)
        if (preset == FilterPreset.AUTO) cameraManager.setAutoEnhance(true)
        if (!fromMode) modeLookActive = false
    }

    /** Manual slider change. Starts from the look that is on screen right now and switches AI Auto off. */
    fun updateManualParameter(updater: (FilterParameters) -> FilterParameters) {
        val base = cameraManager.filterParams.value
        val updated = updater(base).copy(isAutoEnabled = false)
        _manualParams.value = updated
        _isAutoEnhance.value = false
        modeLookActive = false
        cameraManager.updateFilterParameters(updated)
    }

    fun toggleAutoEnhance() {
        if (_isAutoEnhance.value) {
            val frozen = cameraManager.filterParams.value.copy(isAutoEnabled = false)
            _isAutoEnhance.value = false
            _manualParams.value = frozen
            cameraManager.updateFilterParameters(frozen)
        } else {
            applyPreset(FilterPreset.AUTO, fromMode = false)
        }
    }

    fun toggleFilterPanel() {
        _isFilterPanelOpen.value = !_isFilterPanelOpen.value
        if (_isFilterPanelOpen.value) { _isAdjustmentOpen.value = false; _isPulsePanelOpen.value = false }
    }

    fun toggleAdjustmentPanel() {
        _isAdjustmentOpen.value = !_isAdjustmentOpen.value
        if (_isAdjustmentOpen.value) { _isFilterPanelOpen.value = false; _isPulsePanelOpen.value = false }
    }

    fun togglePulsePanel() {
        _isPulsePanelOpen.value = !_isPulsePanelOpen.value
        if (_isPulsePanelOpen.value) { _isFilterPanelOpen.value = false; _isAdjustmentOpen.value = false }
    }

    fun toggleGrid()      { _isGridEnabled.value = !_isGridEnabled.value }
    fun toggleHistogram() { _isHistogramEnabled.value = !_isHistogramEnabled.value }

    fun toggleAudioVisualizer() {
        _isAudioVisualizerOn.value = !_isAudioVisualizerOn.value
        refreshAudio()
    }

    // ── Pulse / audio ─────────────────────────────────────────────────────────

    fun setPulseStyle(style: PulseStyle) {
        _pulseStyle.value = style
        pulseEngine.style = style
    }

    fun setPulseIntensity(value: Float) {
        _pulseIntensity.value = value
        pulseEngine.intensity = value
    }

    fun setAudioPreset(preset: AudioPreset) {
        _audioPreset.value = preset
        audioProcessor.preset = preset
    }

    // ── Modes ─────────────────────────────────────────────────────────────────

    fun setCaptureMode(mode: CaptureMode) {
        if (isRecording.value || isTimelapse.value) return
        soundManager.playModeSwitch()
        val previous = _currentMode.value
        _currentMode.value = mode

        // Pulse effects + music-friendly audio only exist in Pulse mode
        pulseEngine.enabled = mode == CaptureMode.PULSE
        if (mode == CaptureMode.PULSE) {
            pulseEngine.reset()
            _isPulsePanelOpen.value = true
            _isFilterPanelOpen.value = false
            _isAdjustmentOpen.value = false
            if (_audioPreset.value == AudioPreset.NATURAL) setAudioPreset(AudioPreset.MUSIC)
        } else {
            _isPulsePanelOpen.value = false
            if (previous == CaptureMode.PULSE && _audioPreset.value == AudioPreset.MUSIC) setAudioPreset(AudioPreset.NATURAL)
        }

        // Leaving Pro hands exposure back to the smart auto-exposure (the manual EV would otherwise stay forever)
        if (previous == CaptureMode.PRO && mode != CaptureMode.PRO) {
            cameraManager.autoExposure.enableAuto()
            _proExposureIndex.value = 0
            cameraManager.setExposureCompensation(0)
            cameraManager.autoExposure.enableAuto()
        }

        // Look: special modes own the look; leaving them restores AI Auto (unless the user picked something)
        val special = when (mode) {
            CaptureMode.PORTRAIT  -> FilterPreset.PORTRAIT
            CaptureMode.NIGHT     -> FilterPreset.NIGHT
            CaptureMode.CINEMATIC -> FilterPreset.CINEMATIC
            CaptureMode.THERMAL   -> FilterPreset.THERMAL
            else                  -> null
        }
        if (special != null) {
            applyPreset(special, fromMode = true)
            modeLookActive = true
        } else if (mode != CaptureMode.PRO && modeLookActive) {
            applyPreset(FilterPreset.AUTO, fromMode = true)
            modeLookActive = false
        }
        refreshAudio()
    }

    fun openGallery() {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            _message.value = "Could not open gallery"
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() {
        super.onCleared()
        audioProcessor.release()
        soundManager.release()
        cameraManager.release()
    }

    enum class CaptureMode {
        PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, CINEMATIC, THERMAL, TIMELAPSE, PULSE
    }
}
