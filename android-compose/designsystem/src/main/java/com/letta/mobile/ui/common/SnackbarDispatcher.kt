package com.letta.mobile.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayAction
import com.letta.mobile.ui.host.OverlayDurations
import com.letta.mobile.ui.host.OverlayPresentation
import com.letta.mobile.ui.host.OverlayResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val onAction: (() -> Unit)? = null,
)

class SnackbarDispatcher {
    private val _messages = Channel<SnackbarMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun dispatch(message: SnackbarMessage) {
        _messages.trySend(message)
    }

    fun dispatch(message: String) {
        _messages.trySend(SnackbarMessage(message))
    }
}

val LocalSnackbarDispatcher = staticCompositionLocalOf { SnackbarDispatcher() }

/**
 * letta-mobile-ckmub.3.15 (hard cut S13): an app screen's message as a snackbar on the activity's
 * [com.letta.mobile.ui.host.OverlayHost], the one queue every Android surface shares.
 */
fun SnackbarMessage.toOverlay(): Overlay.Snackbar = Overlay.Snackbar(
    message = message,
    presentation = when (duration) {
        SnackbarDuration.Short -> OverlayPresentation.Short
        SnackbarDuration.Long -> OverlayPresentation(durationMillis = OverlayDurations.LONG)
        SnackbarDuration.Indefinite -> OverlayPresentation.Sticky
    },
    response = OverlayResponse(actionLabel?.let { label -> OverlayAction(label) { onAction?.invoke() } }),
)
