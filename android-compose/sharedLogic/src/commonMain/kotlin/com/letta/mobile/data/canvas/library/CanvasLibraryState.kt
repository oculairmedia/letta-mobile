package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.CanvasId

/** Retained library content and a recoverable storage error; failed reads never clear loaded boards. */
data class CanvasLibraryState(
    val documents: List<CanvasDocument> = emptyList(),
    val archived: Set<CanvasId> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
