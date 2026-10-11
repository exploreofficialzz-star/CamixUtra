package com.chastechgroup.camix.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chastechgroup.camix.audio.AudioPreset
import com.chastechgroup.camix.pulse.PulseStyle
import com.chastechgroup.camix.camera.UltraCameraManager
import com.chastechgroup.camix.filter.*
import com.chastechgroup.camix.ui.theme.*
import com.chastechgroup.camix.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun CamixCameraScreen(viewModel: CameraViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope          = rememberCoroutineScope()
    val snackbarHost   = remember { SnackbarHostState() }

    // ── State (audio level / waveform are collected inside their own composables so that the 40 Hz
    //    microphone updates never recompose this whole screen) ───────────────────────────────────
    val currentMode       by viewModel.currentMode.collectAsState()
    val isRecording       by viewModel.isRecording.collectAsState()
    val recordingSeconds  by viewModel.recordingSeconds.collectAsState()
    val flashMode         by viewModel.flashMode.collectAsState()
    val zoomLevel         by viewModel.zoomLevel.collectAsState()
    val detectedScene     by viewModel.detectedScene.collectAsState()
    val isFilterOpen      by viewModel.isFilterPanelOpen.collectAsState()
    val isAdjOpen         by viewModel.isAdjustmentPanelOpen.collectAsState()
    val isPulseOpen       by viewModel.isPulsePanelOpen.collectAsState()
    val isAutoEnhance     by viewModel.isAutoEnhanceEnabled.collectAsState()
    val isGrid            by viewModel.isGridEnabled.collectAsState()
    val isHistogram       by viewModel.isHistogramEnabled.collectAsState()
    val isAudioVis        by viewModel.isAudioVisualizerOn.collectAsState()
    val currentFilter     by viewModel.currentFilter.collectAsState()
    val message           by viewModel.message.collectAsState()
    val filterParams      by viewModel.filterParams.collectAsState()
    val manualParams      by viewModel.manualParams.collectAsState()
    val aeAfLocked        by viewModel.aeAfLocked.collectAsState()
    val isTimelapse       by viewModel.isTimelapse.collectAsState()
    val timelapseCaptured by viewModel.timelapseCaptured.collectAsState()
    val exposureLabel     by viewModel.exposureLabel.collectAsState()
    val isClipping        by viewModel.isClipping.collectAsState()
    val evIndex           by viewModel.evIndex.collectAsState()
    val isProcessing      by viewModel.isProcessing.collectAsState()
    val processingStatus  by viewModel.processingStatus.collectAsState()
    val glFailed          by viewModel.glFailed.collectAsState()
    val histogram         by viewModel.histogram.collectAsState()

    var focusRingPos     by remember { mutableStateOf<Offset?>(null) }
    var focusRingVisible by remember { mutableStateOf(false) }
    var focusRingId      by remember { mutableStateOf(0) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    // The microphone is only open while the app is visible (and a mode needs it)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForeground()
                Lifecycle.Event.ON_STOP  -> viewModel.onAppBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // GPU path: (re)bind the camera. Runs again after an activity re-creation, so the viewfinder never stays black.
    LaunchedEffect(lifecycleOwner, glFailed) {
        if (!glFailed) viewModel.startCamera(lifecycleOwner, null)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(Color.Black)
        ) {

            // ── Viewfinder ──────────────────────────────────────────────────
            if (glFailed) {
                // Safety net: plain CameraX preview (no live FX / video) when the GPU path cannot start
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                            scaleType          = PreviewView.ScaleType.FILL_CENTER
                        }.also { pv -> viewModel.startCamera(lifecycleOwner, pv.surfaceProvider) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    viewModel.attachDisplay(holder.surface, width, height)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    viewModel.attachDisplay(null, 0, 0)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Gesture layer (pinch zoom, tap to focus, long-press AE/AF lock) ──
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (abs(zoom - 1f) > 0.01f) viewModel.pinchZoom(zoom)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { off ->
                                val id = focusRingId + 1
                                focusRingId      = id
                                focusRingPos     = off
                                focusRingVisible = true
                                viewModel.tapToFocus(off.x / size.width, off.y / size.height)
                                scope.launch { delay(2_500); if (focusRingId == id) focusRingVisible = false }
                            },
                            onLongPress = { off ->
                                focusRingId      = focusRingId + 1
                                focusRingPos     = off
                                focusRingVisible = true
                                viewModel.lockAeAf(off.x / size.width, off.y / size.height)
                            }
                        )
                    }
            )

            // ── Grid ────────────────────────────────────────────────────────
            if (isGrid) GridOverlay(Modifier.fillMaxSize())

            // ── Focus ring ──────────────────────────────────────────────────
            AnimatedVisibility(visible = focusRingVisible, modifier = Modifier.fillMaxSize()) {
                focusRingPos?.let { pos ->
                    FocusRing(pos = pos, locked = aeAfLocked, modifier = Modifier.fillMaxSize())
                }
            }

            // ── Processing overlay ───────────────────────────────────────────
            AnimatedVisibility(
                visible  = isProcessing,
                modifier = Modifier.align(Alignment.Center)
            ) {
                ProcessingOverlay(status = processingStatus)
            }

            // ── Clipping warning ─────────────────────────────────────────────
            AnimatedVisibility(
                visible  = isClipping && isAutoEnhance && !isProcessing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
            ) {
                InfoChip("⚠ Highlights clipping — correcting…", ShutterRed)
            }

            // ── Audio Waveform ──────────────────────────────────────────────
            AnimatedVisibility(
                visible  = isAudioVis,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp)
            ) {
                AudioVisualizerOverlay(
                    viewModel = viewModel,
                    modifier  = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)
                )
            }

            // ── Histogram ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible  = isHistogram,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 110.dp, end = 8.dp)
            ) {
                HistogramView(data = histogram, modifier = Modifier.size(100.dp, 60.dp))
            }

            // ── Top info chips ───────────────────────────────────────────────
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ExposureChip(label = exposureLabel, evIndex = evIndex)
                if (isAutoEnhance) SceneChip(scene = detectedScene)
                if (aeAfLocked)    AeAfLockChip { viewModel.unlockAeAf() }
                if (isRecording)   RecordingChip(seconds = recordingSeconds)
                if (isTimelapse)   TimelapseChip(count = timelapseCaptured)
                if (currentMode == CameraViewModel.CaptureMode.PULSE) PulseHud(viewModel)
                if (glFailed) InfoChip("GPU effects unavailable here — plain preview", ShutterRed)
            }

            // ── Top bar ─────────────────────────────────────────────────────
            TopBar(
                flashMode     = flashMode,
                isAutoEnhance = isAutoEnhance,
                isGrid        = isGrid,
                isHistogram   = isHistogram,
                isAudioVis    = isAudioVis,
                onFlash       = { viewModel.toggleFlash() },
                onAutoEnhance = { viewModel.toggleAutoEnhance() },
                onGrid        = { viewModel.toggleGrid() },
                onHistogram   = { viewModel.toggleHistogram() },
                onAudioVis    = { viewModel.toggleAudioVisualizer() },
                modifier      = Modifier.align(Alignment.TopCenter)
            )

            // ── Bottom stack ─────────────────────────────────────────────────
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZoomBar(
                    zoom     = zoomLevel,
                    maxZoom  = viewModel.cameraManager.getMaxZoom(),
                    onChange = { viewModel.setZoom(it) },
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp)
                )

                if (currentMode == CameraViewModel.CaptureMode.PRO) {
                    ProExposureBar(
                        viewModel = viewModel,
                        modifier  = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
                    )
                }

                if (currentMode == CameraViewModel.CaptureMode.TIMELAPSE && !isTimelapse) {
                    TimelapseIntervalSelector(viewModel = viewModel)
                }

                AnimatedVisibility(visible = isPulseOpen && currentMode == CameraViewModel.CaptureMode.PULSE) {
                    PulsePanel(viewModel = viewModel)
                }

                AnimatedVisibility(visible = isFilterOpen) {
                    FilterPanel(current = currentFilter, onSelect = { viewModel.applyFilterPreset(it) })
                }

                AnimatedVisibility(visible = isAdjOpen) {
                    AdjustmentPanel(
                        params   = if (isAutoEnhance) filterParams else manualParams,
                        isAuto   = isAutoEnhance,
                        onChange = { viewModel.updateManualParameter(it) }
                    )
                }

                Spacer(Modifier.height(6.dp))

                ModeSelector(current = currentMode, onSelect = { viewModel.setCaptureMode(it) })

                Spacer(Modifier.height(10.dp))

                CaptureControlsRow(
                    currentMode   = currentMode,
                    isRecording   = isRecording,
                    isTimelapse   = isTimelapse,
                    isProcessing  = isProcessing,
                    onCapture     = {
                        when (currentMode) {
                            CameraViewModel.CaptureMode.VIDEO,
                            CameraViewModel.CaptureMode.PULSE     -> viewModel.toggleRecording()
                            CameraViewModel.CaptureMode.TIMELAPSE ->
                                if (isTimelapse) viewModel.stopTimelapse()
                                else             viewModel.startTimelapse()
                            else -> viewModel.capturePhoto()
                        }
                    },
                    onSwitchCam   = { viewModel.switchCamera(lifecycleOwner) },
                    onFilterClick = { viewModel.toggleFilterPanel() },
                    onAdjClick    = { viewModel.toggleAdjustmentPanel() },
                    onGallery     = { viewModel.openGallery() }
                )
            }
        }
    }
}

@Composable
fun ProcessingOverlay(status: String) {
    Surface(
        color  = Color.Black.copy(alpha = 0.75f),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val inf by rememberInfiniteTransition(label = "proc").animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                label = "rot"
            )
            Canvas(Modifier.size(36.dp)) {
                drawArc(
                    color      = PrimaryPurple,
                    startAngle = inf,
                    sweepAngle = 270f,
                    useCenter  = false,
                    style      = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Text(
                text  = if (status.isNotEmpty()) status else "Processing…",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text  = "Deep Fusion Engine",
                style = MaterialTheme.typography.labelSmall,
                color = AccentTeal
            )
        }
    }
}

// ── Generic info chip ─────────────────────────────────────────────────────────
@Composable
fun InfoChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.25f), shape = RoundedCornerShape(12.dp)) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ── Exposure chip ─────────────────────────────────────────────────────────────
@Composable
fun ExposureChip(label: String, evIndex: Int) {
    val evStr = when { evIndex > 0 -> "+$evIndex"; evIndex < 0 -> "$evIndex"; else -> "±0" }
    Surface(color = Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Outlined.WbSunny, null, tint = GoldYellow, modifier = Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
            Text("EV $evStr", style = MaterialTheme.typography.labelSmall,
                color = GoldYellow, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Scene chip ────────────────────────────────────────────────────────────────
@Composable
fun SceneChip(scene: com.chastechgroup.camix.filter.SceneType) {
    Surface(color = PrimaryPurple.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = AccentTeal, modifier = Modifier.size(13.dp))
            Text(
                text     = "  " + scene.name.replace("_", " "),
                style    = MaterialTheme.typography.labelSmall,
                color    = TextPrimary
            )
        }
    }
}

// ── AE/AF lock chip ───────────────────────────────────────────────────────────
@Composable
fun AeAfLockChip(onUnlock: () -> Unit) {
    Surface(
        color    = GoldYellow.copy(alpha = 0.2f),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable { onUnlock() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, null, tint = GoldYellow, modifier = Modifier.size(12.dp))
            Text("  AE/AF LOCK  ✕",
                style = MaterialTheme.typography.labelSmall,
                color = GoldYellow, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Timelapse chip ────────────────────────────────────────────────────────────
@Composable
fun TimelapseChip(count: Int) {
    val alpha by rememberInfiniteTransition(label = "tl").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ta"
    )
    Surface(color = ShutterRed.copy(alpha = 0.3f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ShutterRed.copy(alpha = alpha)))
            Text("  TIMELAPSE  $count",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Focus ring ────────────────────────────────────────────────────────────────
@Composable
fun FocusRing(pos: Offset, locked: Boolean, modifier: Modifier) {
    val r by animateFloatAsState(
        targetValue      = if (locked) 34f else 44f,
        animationSpec    = spring(stiffness = Spring.StiffnessMedium), label = "fr"
    )
    val color = if (locked) GoldYellow else Color.White
    Canvas(modifier) {
        val rPx = r.dp.toPx()
        drawCircle(color = color, radius = rPx, center = pos, style = Stroke(2.dp.toPx()))
        val arm = 8.dp.toPx(); val sw = 2.dp.toPx()
        listOf(
            Offset(-rPx,-rPx) to listOf(Offset(arm,0f), Offset(0f, arm)),
            Offset( rPx,-rPx) to listOf(Offset(-arm,0f),Offset(0f, arm)),
            Offset(-rPx, rPx) to listOf(Offset(arm,0f), Offset(0f,-arm)),
            Offset( rPx, rPx) to listOf(Offset(-arm,0f),Offset(0f,-arm))
        ).forEach { (c, arms) -> arms.forEach { a -> drawLine(color, pos+c, pos+c+a, sw) } }
    }
}

// ── Grid overlay ─────────────────────────────────────────────────────────────
@Composable
fun GridOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Color.White.copy(alpha = 0.28f); val sw = 1.dp.toPx()
        drawLine(c, Offset(w/3,0f), Offset(w/3,h), sw)
        drawLine(c, Offset(w*2/3,0f), Offset(w*2/3,h), sw)
        drawLine(c, Offset(0f,h/3), Offset(w,h/3), sw)
        drawLine(c, Offset(0f,h*2/3), Offset(w,h*2/3), sw)
    }
}

// ── Audio waveform ────────────────────────────────────────────────────────────
@Composable
fun AudioWaveformView(waveform: FloatArray, rmsLevel: Float, noiseFloor: Float, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))) {
        val w = size.width; val h = size.height; val mid = h / 2
        val step = w / waveform.size.toFloat()
        for (i in waveform.indices) {
            val amp = waveform[i] * mid * 0.9f
            drawLine(CyanBlue.copy(alpha = 0.8f),
                Offset(i * step, mid - amp), Offset(i * step, mid + amp),
                kotlin.math.max(1f, step - 1f))
        }
        val fy = mid - (noiseFloor / -90f) * mid
        drawLine(GoldYellow.copy(alpha = 0.5f), Offset(0f, fy), Offset(w, fy), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    }
}

// ── Histogram ─────────────────────────────────────────────────────────────────
@Composable
fun HistogramView(data: SceneDetector.HistogramData?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))) {
        val d = data ?: return@Canvas
        val w = size.width
        val h = size.height
        val series = listOf(d.r to Color.Red, d.g to Color(0xFF00E676), d.b to Color(0xFF40C4FF))
        for ((bins, color) in series) {
            val path = Path()
            for (i in bins.indices) {
                val x = w * i / (bins.size - 1)
                val y = h - h * 0.92f * bins[i]
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(alpha = 0.7f), style = Stroke(1.5f))
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────
@Composable
fun TopBar(
    flashMode:     UltraCameraManager.FlashMode,
    isAutoEnhance: Boolean,
    isGrid:        Boolean,
    isHistogram:   Boolean,
    isAudioVis:    Boolean,
    onFlash:       () -> Unit,
    onAutoEnhance: () -> Unit,
    onGrid:        () -> Unit,
    onHistogram:   () -> Unit,
    onAudioVis:    () -> Unit,
    modifier:      Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        TopIconBtn(onFlash,
            when (flashMode) {
                UltraCameraManager.FlashMode.OFF  -> Icons.Outlined.FlashOff
                UltraCameraManager.FlashMode.ON   -> Icons.Filled.FlashOn
                UltraCameraManager.FlashMode.AUTO -> Icons.Outlined.FlashAuto
            },
            if (flashMode == UltraCameraManager.FlashMode.OFF) TextSecondary else GoldYellow
        )
        TopIconBtn(onAutoEnhance,
            if (isAutoEnhance) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
            if (isAutoEnhance) AccentTeal else TextSecondary)
        TopIconBtn(onGrid,
            if (isGrid) Icons.Filled.GridOn else Icons.Outlined.GridOn,
            if (isGrid) AccentTeal else TextSecondary)
        TopIconBtn(onHistogram,
            if (isHistogram) Icons.Filled.BarChart else Icons.Outlined.BarChart,
            if (isHistogram) GoldYellow else TextSecondary)
        TopIconBtn(onAudioVis,
            if (isAudioVis) Icons.Filled.VolumeUp else Icons.Outlined.VolumeOff,
            if (isAudioVis) CyanBlue else TextSecondary)
    }
}

@Composable
fun TopIconBtn(onClick: () -> Unit,
               icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

// ── Zoom bar ─────────────────────────────────────────────────────────────────
@Composable
fun ZoomBar(zoom: Float, maxZoom: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ZoomOut, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Slider(
            value = zoom, onValueChange = onChange,
            valueRange = 1f..maxZoom.coerceAtLeast(2f),
            modifier   = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors     = SliderDefaults.colors(
                thumbColor = TextPrimary, activeTrackColor = PrimaryPurple,
                inactiveTrackColor = SurfaceLight)
        )
        Icon(Icons.Outlined.ZoomIn, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("${"%.1f".format(zoom)}×",
            style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

// ── Pro EV bar ────────────────────────────────────────────────────────────────
@Composable
fun ProExposureBar(viewModel: CameraViewModel, modifier: Modifier) {
    val idx by viewModel.proExposureIndex.collectAsState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("EV Compensation", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(if (idx >= 0) "+$idx" else "$idx",
                style = MaterialTheme.typography.labelSmall, color = GoldYellow)
        }
        Slider(
            value = idx.toFloat(), onValueChange = { viewModel.setProExposure(it.toInt()) },
            valueRange = -6f..6f, steps = 11,
            colors = SliderDefaults.colors(thumbColor = GoldYellow, activeTrackColor = GoldYellow)
        )
    }
}

// ── Timelapse interval ────────────────────────────────────────────────────────
@Composable
fun TimelapseIntervalSelector(viewModel: CameraViewModel) {
    val interval by viewModel.timelapseInterval.collectAsState()
    val opts = listOf(1000L to "1s", 2000L to "2s", 5000L to "5s",
        10000L to "10s", 30000L to "30s")
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Interval:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        opts.forEach { (ms, label) ->
            val sel = interval == ms
            Box(Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (sel) PrimaryPurple else ControlBackground)
                .clickable { viewModel.setTimelapseInterval(ms) }
                .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = if (sel) TextPrimary else TextSecondary)
            }
        }
    }
}

// ── Mode selector ─────────────────────────────────────────────────────────────
@Composable
fun ModeSelector(current: CameraViewModel.CaptureMode,
                 onSelect: (CameraViewModel.CaptureMode) -> Unit) {
    val modes = listOf(
        CameraViewModel.CaptureMode.PHOTO     to "Photo",
        CameraViewModel.CaptureMode.VIDEO     to "Video",
        CameraViewModel.CaptureMode.PULSE     to "Pulse",
        CameraViewModel.CaptureMode.PORTRAIT  to "Portrait",
        CameraViewModel.CaptureMode.NIGHT     to "Night",
        CameraViewModel.CaptureMode.CINEMATIC to "Cinematic",
        CameraViewModel.CaptureMode.THERMAL   to "Thermal",
        CameraViewModel.CaptureMode.TIMELAPSE to "Timelapse",
        CameraViewModel.CaptureMode.PRO       to "Pro"
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding        = PaddingValues(horizontal = 12.dp)
    ) {
        items(modes) { (mode, label) ->
            val sel = current == mode
            Box(Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (sel) PrimaryPurple else ControlBackground)
                .clickable { onSelect(mode) }
                .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(label,
                    style      = MaterialTheme.typography.labelMedium,
                    color      = if (sel) TextPrimary else TextSecondary,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ── Filter panel ──────────────────────────────────────────────────────────────
@Composable
fun FilterPanel(current: FilterPreset, onSelect: (FilterPreset) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding        = PaddingValues(horizontal = 12.dp)
    ) {
        items(FilterPreset.entries) { preset ->
            val sel = current == preset
            Column(
                Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) PrimaryPurple.copy(alpha = 0.3f)
                                else    SurfaceDark.copy(alpha = 0.9f))
                    .border(if (sel) 2.dp else 0.dp,
                            if (sel) PrimaryPurple else Color.Transparent,
                            RoundedCornerShape(10.dp))
                    .clickable { onSelect(preset) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(filterColor(preset)))
                Text(preset.displayName,
                    style       = MaterialTheme.typography.labelSmall,
                    color       = if (sel) PrimaryPurple else TextSecondary,
                    textAlign   = TextAlign.Center,
                    maxLines    = 1,
                    modifier    = Modifier.padding(top = 4.dp))
            }
        }
    }
}

fun filterColor(f: FilterPreset): Color = when (f) {
    FilterPreset.NONE       -> Color.DarkGray
    FilterPreset.AUTO       -> PrimaryPurple
    FilterPreset.PORTRAIT   -> Color(0xFFFFC1CC)
    FilterPreset.LANDSCAPE  -> Color(0xFF388E3C)
    FilterPreset.NIGHT      -> Color(0xFF1A237E)
    FilterPreset.VIVID      -> Color(0xFFFF6D00)
    FilterPreset.WARM       -> Color(0xFFFF8F00)
    FilterPreset.COOL       -> Color(0xFF0277BD)
    FilterPreset.DRAMATIC   -> Color(0xFF212121)
    FilterPreset.FADE       -> Color(0xFFD7CCC8)
    FilterPreset.MONO       -> Color(0xFF616161)
    FilterPreset.SEPIA      -> Color(0xFF8D6E63)
    FilterPreset.CINEMATIC  -> Color(0xFF263238)
    FilterPreset.THERMAL    -> Color(0xFFBF360C)
    FilterPreset.ULTRASOUND -> Color(0xFF0D47A1)
    FilterPreset.FOCUS_PEAK -> Color(0xFFB71C1C)
    FilterPreset.DEHAZE     -> Color(0xFF00ACC1)
    FilterPreset.TOON       -> Color(0xFFFFB300)
    FilterPreset.NEON       -> Color(0xFFD500F9)
    FilterPreset.DREAM      -> Color(0xFFF8BBD0)
}

// ── Adjustment panel ──────────────────────────────────────────────────────────
@Composable
fun AdjustmentPanel(
    params:   FilterParameters,
    isAuto:   Boolean,
    onChange: ((FilterParameters) -> FilterParameters) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .height(270.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(SurfaceDark.copy(alpha = 0.97f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scroll)
    ) {
        if (isAuto) Text("AI Auto Active — drag to override",
            style = MaterialTheme.typography.labelMedium, color = AccentTeal,
            modifier = Modifier.padding(bottom = 8.dp))

        adjSlider("Brightness",  params.brightness,    -1f,   1f) { onChange { p -> p.copy(brightness    = it) } }
        adjSlider("Contrast",    params.contrast,      -1f,   1f) { onChange { p -> p.copy(contrast      = it) } }
        adjSlider("Saturation",  params.saturation,    -1f,   1f) { onChange { p -> p.copy(saturation    = it) } }
        adjSlider("Exposure",    params.exposure,      -2f,   2f) { onChange { p -> p.copy(exposure      = it) } }
        adjSlider("Warmth",      params.warmth,        -1f,   1f) { onChange { p -> p.copy(warmth        = it) } }
        adjSlider("Highlights",  params.highlights,    -1f,   1f) { onChange { p -> p.copy(highlights    = it) } }
        adjSlider("Shadows",     params.shadows,       -1f,   1f) { onChange { p -> p.copy(shadows       = it) } }
        adjSlider("Vignette",    params.vignette,       0f,   1f) { onChange { p -> p.copy(vignette      = it) } }
        adjSlider("Sharpen",     params.sharpen,        0f,   2f) { onChange { p -> p.copy(sharpen       = it) } }
        adjSlider("Grain",       params.grain,          0f,   1f) { onChange { p -> p.copy(grain         = it) } }
        adjSlider("Fade",        params.fade,           0f,   1f) { onChange { p -> p.copy(fade          = it) } }
        adjSlider("Clarity",     params.clarityBoost,   0f,   1f) { onChange { p -> p.copy(clarityBoost  = it) } }
        adjSlider("Dehaze",      params.dehaze,          0f,   1f) { onChange { p -> p.copy(dehaze        = it) } }
        adjSlider("Hue",         params.hue,          -180f, 180f){ onChange { p -> p.copy(hue           = it) } }
        adjSlider("Tint",        params.tint,          -1f,   1f) { onChange { p -> p.copy(tint          = it) } }
    }
}

@Composable
fun adjSlider(label: String, value: Float, min: Float, max: Float, onVal: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }
        Slider(
            value = value, onValueChange = onVal, valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = PrimaryPurple, activeTrackColor = PrimaryPurple,
                inactiveTrackColor = SurfaceLight),
            modifier = Modifier.height(28.dp)
        )
    }
}

// ── Capture controls row ──────────────────────────────────────────────────────
@Composable
fun CaptureControlsRow(
    currentMode:   CameraViewModel.CaptureMode,
    isRecording:   Boolean,
    isTimelapse:   Boolean,
    isProcessing:  Boolean,
    onCapture:     () -> Unit,
    onSwitchCam:   () -> Unit,
    onFilterClick: () -> Unit,
    onAdjClick:    () -> Unit,
    onGallery:     () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        IconButton(onClick = onGallery, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.PhotoLibrary, "Gallery",
                tint = TextPrimary, modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onFilterClick, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.PhotoFilter, "Filters",
                tint = TextPrimary, modifier = Modifier.size(28.dp))
        }
        ShutterBtn(
            mode        = currentMode,
            isRecording = isRecording,
            isTimelapse = isTimelapse,
            isProcessing= isProcessing,
            onClick     = onCapture
        )
        IconButton(onClick = onAdjClick, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Tune, "Adjust",
                tint = TextPrimary, modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onSwitchCam, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Cameraswitch, "Switch",
                tint = TextPrimary, modifier = Modifier.size(28.dp))
        }
    }
}

// ── Shutter button ────────────────────────────────────────────────────────────
@Composable
fun ShutterBtn(
    mode:         CameraViewModel.CaptureMode,
    isRecording:  Boolean,
    isTimelapse:  Boolean,
    isProcessing: Boolean,
    onClick:      () -> Unit
) {
    val isVideoMode = mode == CameraViewModel.CaptureMode.VIDEO ||
                      mode == CameraViewModel.CaptureMode.PULSE
    val isTLMode    = mode == CameraViewModel.CaptureMode.TIMELAPSE
    val active      = (isVideoMode && isRecording) || (isTLMode && isTimelapse)

    val scale by animateFloatAsState(
        targetValue   = if (active) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "sh"
    )

    val outerColor = when {
        isProcessing              -> PrimaryPurple
        active && isVideoMode     -> ShutterRed
        active && isTLMode        -> WarningOrange
        isVideoMode || isTLMode   -> ShutterRed
        else                      -> TextPrimary
    }

    Box(
        Modifier
            .size(76.dp)
            .scale(scale)
            .border(4.dp, outerColor, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (active) outerColor else Color.Transparent)
            .clickable(enabled = !isProcessing) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            isProcessing -> {
                val inf by rememberInfiniteTransition(label = "pb").animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r"
                )
                Canvas(Modifier.size(28.dp)) {
                    drawArc(PrimaryPurple, inf, 270f, false,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            active && isVideoMode ->
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(TextPrimary))
            active && isTLMode ->
                Icon(Icons.Filled.Pause, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
            isTLMode ->
                Icon(Icons.Filled.Timelapse, null, tint = WarningOrange, modifier = Modifier.size(30.dp))
            isVideoMode ->
                Box(Modifier.size(22.dp).clip(CircleShape).background(ShutterRed))
        }
    }
}


// ── Audio visualizer (collects the fast microphone flows itself) ─────────────────
@Composable
fun AudioVisualizerOverlay(viewModel: CameraViewModel, modifier: Modifier) {
    val waveform by viewModel.waveform.collectAsState()
    val rms      by viewModel.rmsLevel.collectAsState()
    val floor    by viewModel.noiseFloorDb.collectAsState()
    AudioWaveformView(waveform = waveform, rmsLevel = rms, noiseFloor = floor, modifier = modifier)
}

// ── Recording chip ───────────────────────────────────────────────────────────────
@Composable
fun RecordingChip(seconds: Int) {
    val alpha by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ra"
    )
    Surface(color = ShutterRed.copy(alpha = 0.3f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ShutterRed.copy(alpha = alpha)))
            Text("  REC  %02d:%02d".format(seconds / 60, seconds % 60),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Pulse HUD: beat dot + tempo ─────────────────────────────────────────────────
@Composable
fun PulseHud(viewModel: CameraViewModel) {
    val bpm     by viewModel.bpm.collectAsState()
    val kicks   by viewModel.kickCount.collectAsState()
    val running by viewModel.audioRunning.collectAsState()
    val beat = remember { Animatable(0f) }
    LaunchedEffect(kicks) {
        beat.snapTo(1f)
        beat.animateTo(0f, tween(260))
    }
    val label = when {
        !running  -> "  mic off"
        bpm > 0f  -> "  " + bpm.roundToInt() + " BPM"
        else      -> "  listening…"
    }
    Surface(color = PrimaryPurple.copy(alpha = 0.28f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size((8f + 8f * beat.value).dp).clip(CircleShape).background(CyanBlue))
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Pulse panel ─────────────────────────────────────────────────────────────────
@Composable
fun PulsePanel(viewModel: CameraViewModel) {
    val style     by viewModel.pulseStyle.collectAsState()
    val intensity by viewModel.pulseIntensity.collectAsState()
    val preset    by viewModel.audioPreset.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark.copy(alpha = 0.92f))
            .padding(10.dp)
    ) {
        Text("PULSE — the camera listens. Play music or talk; the picture moves with the beat.",
            style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PulseStyle.entries) { s ->
                PulseChip(label = s.label, selected = s == style) { viewModel.setPulseStyle(s) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intensity", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Slider(
                value = intensity,
                onValueChange = { viewModel.setPulseIntensity(it) },
                valueRange = 0f..1.5f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
        }
        Text("Recorded sound", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioPreset.entries.forEach { p ->
                PulseChip(label = p.label, selected = p == preset) { viewModel.setAudioPreset(p) }
            }
        }
    }
}

@Composable
private fun PulseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) PrimaryPurple else ControlBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
