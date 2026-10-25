package com.letta.mobile.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerContext
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewers
import com.letta.mobile.ui.canvas.plugin.LocalCanvasDocumentCanvasContent
import com.letta.mobile.ui.canvas.plugin.LocalCanvasDocumentViewers

/**
 * Selects a rendering of [session] while keeping that exact session mounted by its owner. Viewer
 * composition receives immutable document context, never a session mutation interface.
 */
@Composable
internal fun CanvasDocumentViewerHost(
    session: CanvasSession,
    modifier: Modifier = Modifier,
    chromeTopInset: Dp = 0.dp,
    beforeViewerChange: suspend () -> Boolean = { true },
    canvasContent: @Composable (Modifier) -> Unit,
) {
    var selectedViewerId by remember(session.canvasId) {
        mutableStateOf(CanvasDocumentViewerId.CANVAS)
    }
    val scope = rememberCoroutineScope()
    var switching by remember(session) { mutableStateOf(false) }
    val document by session.document.collectAsState()
    val viewers = LocalCanvasDocumentViewers.current
    LaunchedEffect(viewers, selectedViewerId) {
        if (!viewers.contains(selectedViewerId)) selectedViewerId = CanvasDocumentViewerId.CANVAS
    }
    val selectedViewer = viewers.viewerFor(selectedViewerId)
    val context = CanvasDocumentViewerContext(
        canvasId = session.canvasId,
        revision = document?.revision,
        sceneJson = document?.sceneJson,
    )

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalCanvasDocumentCanvasContent provides { _, canvasModifier -> canvasContent(canvasModifier) },
        ) {
            selectedViewer.render(context, Modifier.fillMaxSize())
        }
        CanvasDocumentViewerToggle(
            viewers = viewers,
            selectedId = selectedViewer.id,
            onSelect = { id ->
                if (!switching && id != selectedViewer.id) {
                    switching = true
                    scope.launch {
                        try {
                            if (beforeViewerChange()) selectedViewerId = id
                        } finally {
                            switching = false
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = chromeTopInset + 56.dp),
        )
    }
}

@Composable
private fun CanvasDocumentViewerToggle(
    viewers: CanvasDocumentViewers,
    selectedId: CanvasDocumentViewerId,
    onSelect: (CanvasDocumentViewerId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.testTag(CANVAS_VIEWER_TOGGLE_TAG).horizontalScroll(rememberScrollState())) {
        viewers.all.forEach { viewer ->
            FilterChip(
                selected = selectedId == viewer.id,
                onClick = { onSelect(viewer.id) },
                label = { Text(viewer.label()) },
                modifier = Modifier.testTag(canvasViewerOptionTag(viewer.id)),
            )
        }
    }
}

internal fun canvasViewerOptionTag(viewerId: CanvasDocumentViewerId): String = "canvas-viewer-${viewerId.value}"
internal const val CANVAS_VIEWER_TOGGLE_TAG = "canvas-viewer-toggle"
