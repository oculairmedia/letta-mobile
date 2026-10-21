package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.CanvasId

/** Retained library content and a recoverable storage error; failed reads never clear loaded boards. */
data class CanvasLibraryState(
    /** Last successfully loaded board snapshots, retained when a later read fails. */
    val documents: List<CanvasDocument> = emptyList(),
    /** Archived IDs from the last successful refresh or archive update. */
    val archived: Set<CanvasId> = emptySet(),
    /** True while a serialized library operation is reading or writing storage. */
    val isLoading: Boolean = false,
    /** Retryable notice; a successful library refresh clears it. */
    val error: String? = null,
)
