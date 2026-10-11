package com.chastechgroup.camix

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.chastechgroup.camix.ui.screens.CamixCameraScreen
import com.chastechgroup.camix.ui.theme.CamixTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val requiredPermissions: Array<String> get() {
        val base = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            base.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Only the camera is mandatory. The microphone is optional (Pulse + sound in videos).
        permissionsGranted = results[Manifest.permission.CAMERA] == true || cameraGranted()
    }

    private fun cameraGranted() =
        checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissionsGranted = cameraGranted()
        setContent {
            CamixTheme {
                AnimatedContent(targetState = permissionsGranted,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "permTransition") { granted ->
                    if (granted) CamixCameraScreen()
                    else PermissionRationale { permissionLauncher.launch(requiredPermissions) }
                }
            }
        }
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions)
    }
}

@Composable
fun PermissionRationale(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0F)), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)) {
            Text("CamixUltra needs the camera to work. Microphone access is optional: it powers Pulse (visuals that move with the beat) and sound in your videos.",
                style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center)
            Button(onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))) {
                Text("Grant Permissions")
            }
        }
    }
}
