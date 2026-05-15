package com.letta.mobile.ui.components.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * letta-mobile-rl0d / letta-mobile-7w57: hold-to-talk affordance.
 *
 * Pure UI — no ViewModel coupling so the designsystem stays Hilt-free.
 * The caller owns the speech-recognition state machine
 * (VoiceInputViewModel in :app).
 *
 * Gesture semantics (matches the Edge Gallery original):
 *   - Press down → [onStart].
 *   - Slide upward past [cancelThresholdDp] → [onCancel] fires once;
 *     subsequent release is a no-op (no [onStop]).
 *   - Clean release without exceeding the threshold → [onStop].
 *
 * Cancel-on-drag uses an `awaitEachGesture` loop rather than
 * `detectTapGestures(onPress)` so the pointer's y-delta from the
 * initial down can be tracked. Tap-gesture detectors don't surface
 * per-frame movement deltas.
 */
@Composable
fun HoldToDictateButton(
    isRecognizing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    cancelThresholdDp: Int = 100,
) {
    var recordAudioPermissionGranted by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val recordAudioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted ->
            if (permissionGranted) {
                recordAudioPermissionGranted = true
            }
        }

    LaunchedEffect(Unit) {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
                recordAudioPermissionGranted = true
            }
            else -> {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // letta-mobile-7w57: rememberUpdatedState so the gesture coroutine
    // — which is captured into pointerInput's lambda — always sees the
    // latest callback closures and `enabled` flag without re-keying the
    // pointer input (which would interrupt an in-flight press).
    val onStartUpdated by rememberUpdatedState(onStart)
    val onStopUpdated by rememberUpdatedState(onStop)
    val onCancelUpdated by rememberUpdatedState(onCancel)
    val enabledUpdated by rememberUpdatedState(enabled)

    if (recordAudioPermissionGranted) {
        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    val cancelThresholdPx = cancelThresholdDp.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabledUpdated) return@awaitEachGesture
                        val startY = down.position.y
                        var cancelled = false
                        onStartUpdated()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (!change.pressed) break
                            if (!cancelled) {
                                val dy = change.position.y - startY
                                if (dy < -cancelThresholdPx) {
                                    cancelled = true
                                    onCancelUpdated()
                                }
                            }
                        }
                        if (!cancelled) onStopUpdated()
                    }
                }
                .clip(CircleShape)
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                .background(if (isRecognizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Hold to talk",
                tint = if (isRecognizing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

