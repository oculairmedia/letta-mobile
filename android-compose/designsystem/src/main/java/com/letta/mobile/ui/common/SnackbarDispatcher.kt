package com.letta.mobile.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
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
