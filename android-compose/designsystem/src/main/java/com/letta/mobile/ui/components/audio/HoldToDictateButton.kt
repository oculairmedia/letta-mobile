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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.LettaDimens

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
 *   - An ACTION_CANCEL, or the button leaving composition mid-hold → [onCancel]
 *     (letta-mobile-wlo08), so the recognizer never keeps recording.
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
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

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

    // letta-mobile-wlo08: leaving composition mid-hold must stop the recognizer too; [hold] makes
    // the dispose and the gesture's own cleanup end the hold exactly once.
    val hold = remember { DictationHold() }
    DisposableEffect(hold) {
        onDispose { if (hold.end()) onCancelUpdated() }
    }

    if (recordAudioPermissionGranted) {
        Box(
            modifier = modifier
                .size(LettaDimens.Orb.railSlotWidth)
                .testTag(HOLD_TO_DICTATE_BUTTON_TEST_TAG)
                .pointerInput(Unit) {
                    val cancelThresholdPx = cancelThresholdDp.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabledUpdated) return@awaitEachGesture
                        hold.begin()
                        HapticEffects.gestureThreshold(haptic, view)
                        onStartUpdated()
                        try {
                            val end = awaitDictationHoldEnd(down, cancelThresholdPx)
                            if (hold.end()) {
                                if (end == DictationHoldEnd.Commit) {
                                    HapticEffects.confirm(haptic, view)
                                    onStopUpdated()
                                } else {
                                    HapticEffects.reject(haptic, view)
                                    onCancelUpdated()
                                }
                            }
                        } finally {
                            // letta-mobile-wlo08: the node detached mid-hold (this coroutine was
                            // cancelled). Stop the recognizer without committing.
                            if (hold.end()) onCancelUpdated()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            HoldToDictateVisual(isRecognizing = isRecognizing, enabled = enabled)
        }
    }
}

/** letta-mobile-wlo08: how one hold ended. */
internal enum class DictationHoldEnd { Commit, Cancel }

/**
 * A consumed release is an ACTION_CANCEL Compose synthesised (focus loss, a system gesture taking
 * the stream) or a release another node claimed: it cancels, it never commits the dictation.
 */
internal fun dictationReleaseOutcome(releaseConsumed: Boolean): DictationHoldEnd =
    if (releaseConsumed) DictationHoldEnd.Cancel else DictationHoldEnd.Commit

/** Whether a hold is in progress; [end] reports true only for the first ending. */
private class DictationHold {
    private var active = false

    fun begin() {
        active = true
    }

    fun end(): Boolean = active.also { active = false }
}

/** Follows the pressed pointer until the hold ends: a release, a cancel, or a slide up past the threshold. */
private suspend fun AwaitPointerEventScope.awaitDictationHoldEnd(
    down: PointerInputChange,
    cancelThresholdPx: Float,
): DictationHoldEnd {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
            ?: return DictationHoldEnd.Cancel
        if (!change.pressed) return dictationReleaseOutcome(change.isConsumed)
        if (change.position.y - down.position.y < -cancelThresholdPx) return DictationHoldEnd.Cancel
    }
}

@Composable
private fun HoldToDictateVisual(isRecognizing: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(LettaDimens.Orb.railSlotHeight)
            .clip(CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .background(
                if (isRecognizing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Hold to talk",
            tint = if (isRecognizing) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

internal const val HOLD_TO_DICTATE_BUTTON_TEST_TAG = "hold_to_dictate_button"
