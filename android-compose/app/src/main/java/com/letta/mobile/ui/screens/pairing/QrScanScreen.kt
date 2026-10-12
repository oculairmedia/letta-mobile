package com.letta.mobile.ui.screens.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.ui.pairing.PairCodeEntry
import com.letta.mobile.ui.pairing.PairingStatusCard
import com.letta.mobile.ui.pairing.PairingStrings
import com.letta.mobile.ui.icons.LettaIcons
import java.util.concurrent.Executors
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Scan-to-pair on the phone (letta-mobile-g2d2i, completed by dpv7m.2).
 * CameraX + [QrScanAnalyzer] supply the scanned text; the shared
 * [com.letta.mobile.data.pairing.PairingFlow] (via [PairingViewModel]) parses,
 * dials the host, redeems the one-time invite, saves the host and makes it
 * active. [onPaired] then opens chat. A paste field is the fallback when the
 * camera is unavailable; [initialLink] is a pairing link opened from outside
 * the app (deep link).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onPaired: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialLink: String? = null,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val camera = rememberCameraPermission()
    val pairingState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialLink) { initialLink?.let(viewModel::submit) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_pairing_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                camera.granted -> CameraPreview(onDecoded = viewModel::submit)
                camera.denied -> PermissionDeniedContent(onOpenSettings = camera.openSettings, onRetryPermission = camera.request)
                else -> CircularProgressIndicator()
            }
            ScanBottomSheet(
                state = pairingState,
                onSubmit = viewModel::submit,
                onScanAgain = viewModel::reset,
                onOpenChat = onPaired,
            )
        }
    }
}

/** Shared status card over the preview, plus the paste fallback while idle. */
@Composable
private fun ScanBottomSheet(
    state: PairingState,
    onSubmit: (String) -> Unit,
    onScanAgain: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(LettaDimens.Space.lg),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (state is PairingState.Idle) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier.padding(LettaDimens.Space.lg),
                    verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                ) {
                    Text(PairingStrings.SCAN_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PairCodeEntry(onSubmit = onSubmit)
                }
            }
        } else {
            PairingStatusCard(state = state, onScanAgain = onScanAgain, onOpenChat = onOpenChat)
        }
    }
}

/** Camera permission state for the scanner, requested once on entry. */
private class CameraPermission(
    val granted: Boolean,
    val denied: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
private fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    return CameraPermission(
        granted = granted,
        denied = denied,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, QrScanAnalyzer(onDecoded)) }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Exception) {
                        // Binding can race a lifecycle that's already moved
                        // past CREATED by the time the future resolves (e.g.
                        // the user backed out mid-init) — nothing actionable.
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

@Composable
private fun PermissionDeniedContent(
    onOpenSettings: () -> Unit,
    onRetryPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LettaDimens.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Icon(
            imageVector = LettaIcons.Camera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.pairing_camera_permission_denied),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.pairing_open_settings))
        }
        OutlinedButton(onClick = onRetryPermission) {
            Text(stringResource(R.string.pairing_scan_again))
        }
    }
}
