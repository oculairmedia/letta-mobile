package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.CanvasId
import kotlin.jvm.JvmInline

/** Stable, typed identity for a native sharedUI document viewer. */
@JvmInline
value class CanvasDocumentViewerId(val value: String) {
    init {
        require(value.isNotBlank()) { "a canvas document viewer needs a non-blank ID" }
    }

    companion object {
        val CANVAS = CanvasDocumentViewerId("canvas")
        val TEXT = CanvasDocumentViewerId("text")
    }
}

/** Immutable canvas data supplied to a document viewer. A null revision and scene mean loading. */
@Immutable
data class CanvasDocumentViewerContext(
    val canvasId: CanvasId,
    val revision: Long?,
    val sceneJson: String?,
)

/** Renders one canvas document without receiving its mutable session or write operations. */
typealias CanvasDocumentViewerRenderer = @Composable (
    context: CanvasDocumentViewerContext,
    modifier: Modifier,
) -> Unit

/** A native sharedUI renderer for a canvas document. */
@Immutable
class CanvasDocumentViewer(
    val id: CanvasDocumentViewerId,
    val label: @Composable () -> String,
    val render: CanvasDocumentViewerRenderer,
)
