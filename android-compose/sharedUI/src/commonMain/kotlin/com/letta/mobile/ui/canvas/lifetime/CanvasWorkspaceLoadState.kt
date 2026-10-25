package com.letta.mobile.ui.canvas.lifetime

import androidx.compose.runtime.State

/** The shared workspace load result, kept separate from the already full canvas UI package. */
internal enum class CanvasWorkspaceLoadState { Loading, Ready, Failed }

/** The visible load state and its explicit user retry action. */
internal data class CanvasWorkspaceSessionLifecycle(
    val loadState: State<CanvasWorkspaceLoadState>,
    val retry: () -> Unit,
)
