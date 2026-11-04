package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.ui.plugin.RuntimeObjectView
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_viewer_canvas
import com.letta.mobile.sharedui.resources.canvas_viewer_text
import com.letta.mobile.ui.canvas.viewer.CanvasTextViewer
import org.jetbrains.compose.resources.stringResource

/** Trusted document views: legacy canvas adapters and neutral object views in one registry. */
@Immutable
class CanvasDocumentViewers private constructor(
    private val entries: List<Contribution>,
    private val fallbackId: CanvasDocumentViewerId,
) {
    /** Viewers shown in the workspace's selector, in registration order. */
    val all: List<CanvasDocumentViewer> get() = entries.filterIsInstance<Contribution.Canvas>().map { it.viewer }

    /** Add or replace an extension. The built-in canvas identity is reserved. */
    fun register(viewer: CanvasDocumentViewer): CanvasDocumentViewers = register(Contribution.Canvas(viewer))

    private fun register(contribution: Contribution): CanvasDocumentViewers {
        require(contribution.id.value != fallbackId.value) { "the fallback canvas document viewer cannot be replaced" }
        val replacement = if (entries.any { it.id == contribution.id }) entries.map { if (it.id == contribution.id) contribution else it }
        else entries + contribution
        return CanvasDocumentViewers(replacement, fallbackId)
    }

    /** Remove a registered viewer. The built-in canvas viewer cannot be removed. */
    fun unregister(id: CanvasDocumentViewerId): CanvasDocumentViewers = unregister(RuntimeViewId(id.value))

    /** Resolve a canvas adapter, using the built-in canvas after its extension is removed. */
    fun viewerFor(id: CanvasDocumentViewerId): CanvasDocumentViewer =
        all.firstOrNull { it.id == id } ?: all.first { it.id == fallbackId }

    fun contains(id: CanvasDocumentViewerId): Boolean = all.any { it.id == id }

    /** Trusted neutral views share this registration path; they need no canvas adapter. */
    fun register(view: RuntimeObjectView): CanvasDocumentViewers = register(Contribution.Object(view))

    /** Remove either kind of contribution by its stable ID. */
    fun unregister(viewId: RuntimeViewId): CanvasDocumentViewers {
        require(viewId.value != fallbackId.value) { "the fallback canvas document viewer cannot be removed" }
        return CanvasDocumentViewers(entries.filterNot { it.id == viewId }, fallbackId)
    }

    /** Discover registered neutral implementations and their schema/contract metadata. */
    val runtimeViews: List<RuntimeObjectView> get() = entries.filterIsInstance<Contribution.Object>().map { it.view }

    private sealed interface Contribution {
        val id: RuntimeViewId

        class Canvas(val viewer: CanvasDocumentViewer) : Contribution {
            override val id: RuntimeViewId get() = RuntimeViewId(viewer.id.value)
        }

        class Object(val view: RuntimeObjectView) : Contribution {
            override val id: RuntimeViewId get() = view.descriptor.id
        }
    }

    companion object {
        /** Core's canvas and read-only text presentations. */
        val Core: CanvasDocumentViewers = CanvasDocumentViewers(
            entries = listOf(
                Contribution.Canvas(CanvasDocumentViewer(
                    id = CanvasDocumentViewerId.CANVAS,
                    label = { stringResource(Res.string.canvas_viewer_canvas) },
                    render = { context, modifier ->
                        LocalCanvasDocumentCanvasContent.current(context, modifier)
                    },
                )),
                Contribution.Canvas(CanvasDocumentViewer(
                    id = CanvasDocumentViewerId.TEXT,
                    label = { stringResource(Res.string.canvas_viewer_text) },
                    render = { context, modifier -> CanvasTextViewer(context.sceneJson, modifier) },
                )),
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
