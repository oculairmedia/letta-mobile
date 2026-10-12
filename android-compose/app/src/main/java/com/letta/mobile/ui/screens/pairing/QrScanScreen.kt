package com.letta.mobile.ui.screens.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.pairing.CameraAccess
import com.letta.mobile.ui.pairing.PairingScan
import com.letta.mobile.ui.pairing.PairingScanScreen
import com.letta.mobile.ui.pairing.ScanCamera
import com.letta.mobile.ui.pairing.TorchControl
import java.util.concurrent.Executors

/**
 * Android CONTAINER for the shared "Scan to connect" screen (letta-mobile-dpv7m.5).
 * Platform-UI-Reason: camera — the CameraX preview surface + QR analyzer, the
 * runtime camera permission and the torch are Android services; every pixel
 * around them (viewfinder, rationale, trust prompt, states) is
 * [PairingScanScreen] in sharedUI.
 */
@Composable
fun QrScanScreen(
    onPaired: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A link opened from outside the app (deep link) is consumed once, from memory, and stops at the trust prompt.
    LaunchedEffect(Unit) { PendingPairLink.take()?.let(viewModel::submit) }
    val actions = remember(viewModel) { viewModel.actions(onOpenChat = onPaired) }
    PairingScanScreen(
        scan = PairingScan(state, rememberAndroidScanCamera(), actions),
        clock = System::currentTimeMillis,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
private fun rememberAndroidScanCamera(): ScanCamera {
    val context = LocalContext.current
    var access by remember {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(if (granted) CameraAccess.GRANTED else CameraAccess.NEEDS_RATIONALE)
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        access = if (ok) CameraAccess.GRANTED else CameraAccess.DENIED
    }
    // Back from system settings with the camera allowed: pick it up without a restart.
    LifecycleResumeEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            access = CameraAccess.GRANTED
        }
        onPauseOrDispose { }
    }
    val bound = camera
    return ScanCamera(
        access = access,
        onRequestAccess = {
            access = CameraAccess.REQUESTING
            launcher.launch(Manifest.permission.CAMERA)
        },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)),
            )
        },
        torch = bound?.takeIf { it.cameraInfo.hasFlashUnit() }?.let { cam ->
            TorchControl(on = torchOn) {
                torchOn = !torchOn
                cam.cameraControl.enableTorch(torchOn)
            }
        },
        preview = { onDecoded -> CameraPreview(onDecoded = onDecoded, onBound = { camera = it }) },
    )
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit, onBound: (Camera) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnDecoded by rememberUpdatedState(onDecoded)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    // Decoded text is handed to the shared screen on the main thread.
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, QrScanAnalyzer { text -> mainExecutor.execute { latestOnDecoded(text) } }) }
                    try {
                        cameraProvider.unbindAll()
                        onBound(cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
                    } catch (_: Exception) {
                        // Binding can race a lifecycle that's already moved
                        // past CREATED by the time the future resolves (e.g.
                        // the user backed out mid-init) — nothing actionable.
                    }
                },
                mainExecutor,
            )
            previewView
        },
    )
}
