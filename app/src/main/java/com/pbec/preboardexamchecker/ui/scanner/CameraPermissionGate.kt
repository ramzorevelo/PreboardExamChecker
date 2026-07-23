package com.pbec.preboardexamchecker.ui.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun isCameraGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

/** Reactive CAMERA-permission status plus a [request] trigger for the system dialog. */
@Stable
class CameraPermissionState internal constructor(initialGranted: Boolean) {
    var hasPermission by mutableStateOf(initialGranted)
        internal set

    internal var requester: () -> Unit = {}

    /** Re-shows the system permission dialog (a no-op once the user picked "Don't allow"). */
    fun request() = requester()
}

/**
 * Owns the CAMERA-permission lifecycle shared by the scan screens: the initial check (synchronous,
 * so a granted permission never flashes the request UI), the auto-request on first show, and the
 * ON_RESUME re-check that picks up a grant made from App Settings — the only path left once the
 * system dialog has been permanently dismissed.
 */
@Composable
internal fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val state = remember { CameraPermissionState(isCameraGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> state.hasPermission = granted }
    state.requester = remember(launcher) { { launcher.launch(Manifest.permission.CAMERA) } }

    LaunchedEffect(Unit) {
        if (!state.hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.hasPermission = isCameraGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}

/**
 * Full-screen prompt shown while CAMERA is not granted. [onRequest] re-triggers the system dialog;
 * the App Settings button is the recovery path once that dialog no longer appears.
 */
@Composable
internal fun CameraPermissionRequest(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "Camera permission required to scan answer sheets.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("Grant Permission") }
            Spacer(Modifier.height(12.dp))
            Text(
                "If nothing happens when you tap Grant, permission was blocked earlier. Enable Camera in App Settings, then return here.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Open App Settings") }
        }
    }
}
