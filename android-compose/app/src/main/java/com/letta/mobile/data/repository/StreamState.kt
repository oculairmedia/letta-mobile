package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppMessage

sealed interface StreamState {
    data object Sending : StreamState
    data class Streaming(val messages: List<AppMessage>) : StreamState
    data class ToolExecution(val toolName: String) : StreamState
    data class Complete(val messages: List<AppMessage>) : StreamState
    data class Error(val message: String) : StreamState
}
