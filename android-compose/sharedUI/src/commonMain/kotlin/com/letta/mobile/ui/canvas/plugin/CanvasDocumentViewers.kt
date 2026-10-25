package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_viewer_canvas
import com.letta.mobile.sharedui.resources.canvas_viewer_text
import com.letta.mobile.ui.canvas.viewer.CanvasTextViewer
import org.jetbrains.compose.resources.stringResource

/** Registered renderers available to a canvas workspace. */
@Immutable
class CanvasDocumentViewers private constructor(
    private val byId: Map<CanvasDocumentViewerId, CanvasDocumentViewer>,
    private val fallbackId: CanvasDocumentViewerId,
) {
    /** Viewers shown in the workspace's selector, in registration order. */
    val all: List<CanvasDocumentViewer> get() = byId.values.toList()

    /** Add or replace an extension. The built-in canvas identity is reserved. */
    fun register(viewer: CanvasDocumentViewer): CanvasDocumentViewers {
        require(viewer.id != fallbackId) { "the fallback canvas document viewer cannot be replaced" }
        return CanvasDocumentViewers(byId + (viewer.id to viewer), fallbackId)
    }

    /** Remove a registered viewer. The built-in canvas viewer cannot be removed. */
    fun unregister(id: CanvasDocumentViewerId): CanvasDocumentViewers {
        require(id != fallbackId) { "the fallback canvas document viewer cannot be removed" }
        return if (id in byId) CanvasDocumentViewers(byId - id, fallbackId) else this
    }

    /** Resolve an ID, using the built-in canvas viewer after an extension is removed. */
    fun viewerFor(id: CanvasDocumentViewerId): CanvasDocumentViewer =
        byId[id] ?: byId.getValue(fallbackId)

    /** Whether this registry currently contains [id]. */
    fun contains(id: CanvasDocumentViewerId): Boolean = id in byId

    companion object {
        /** Core's canvas and read-only text presentations. */
        val Core: CanvasDocumentViewers = CanvasDocumentViewers(
            byId = linkedMapOf(
                CanvasDocumentViewerId.CANVAS to CanvasDocumentViewer(
                    id = CanvasDocumentViewerId.CANVAS,
                    label = { stringResource(Res.string.canvas_viewer_canvas) },
                    render = { context, modifier ->
                        LocalCanvasDocumentCanvasContent.current(context, modifier)
                    },
                ),
                CanvasDocumentViewerId.TEXT to CanvasDocumentViewer(
                    id = CanvasDocumentViewerId.TEXT,
                    label = { stringResource(Res.string.canvas_viewer_text) },
                    render = { context, modifier -> CanvasTextViewer(context.sceneJson, modifier) },
                ),
            ),
            fallbackId = CanvasDocumentViewerId.CANVAS,
        )
    }
}

/** The viewer registry in the current canvas workspace. */
val LocalCanvasDocumentViewers = staticCompositionLocalOf { CanvasDocumentViewers.Core }

/**
 * Provide native sharedUI viewers to [content]. The registration block runs on every composition,
 * so state read by the block updates the active registry rather than being captured by a remembered
 * lambda.
 */
@Composable
fun ProvideCanvasDocumentViewers(
    registrations: CanvasDocumentViewers.() -> CanvasDocumentViewers,
    content: @Composable () -> Unit,
) {
    val base = LocalCanvasDocumentViewers.current
    val viewers = base.registrations()
    CompositionLocalProvider(LocalCanvasDocumentViewers provides viewers, content = content)
}

internal typealias CanvasDocumentCanvasContent = @Composable (
    context: CanvasDocumentViewerContext,
    modifier: Modifier,
) -> Unit

/** Only the built-in canvas viewer sees the host's board binding; extensions receive data only. */
internal val LocalCanvasDocumentCanvasContent =
    staticCompositionLocalOf<CanvasDocumentCanvasContent> {
        { _, _ -> error("canvas board content is not bound") }
    }
