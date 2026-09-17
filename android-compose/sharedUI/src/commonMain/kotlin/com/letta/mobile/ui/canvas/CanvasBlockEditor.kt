package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CANVAS_PRIMARY_DOCUMENT_ID
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import io.github.linreal.cascade.editor.serialization.loadFromJson
import io.github.linreal.cascade.editor.serialization.toJson
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.EditorStateHolder
import io.github.linreal.cascade.editor.state.rememberEditorState
import io.github.linreal.cascade.editor.theme.CascadeEditorTheme
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreview
import io.github.linreal.cascade.editor.ui.CascadeDocumentPreviewConfig
import io.github.linreal.cascade.editor.ui.CascadeEditor
import io.github.linreal.cascade.editor.ui.CascadeEditorConfig
import io.github.linreal.cascade.editor.ui.ExperimentalCascadePreviewApi
import io.github.linreal.cascade.editor.ui.ToolbarSlot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The canvas's block document (its notes), edited with the Cascade block editor and persisted
 * through the canvas op log as `set_document` ops, so peers and the agent see the same text.
 *
 * Persistence is commit-based: the editor's JSON is compared to what the session holds on a short
 * cadence and written only when it changed, which coalesces typing into one op per pause. A newer
 * document arriving through the session (another peer, the agent, a checkpoint restore) reloads
 * the editor.
 */
@Composable
fun CanvasBlockEditor(
    session: CanvasSession,
    modifier: Modifier = Modifier,
    documentId: String = CANVAS_PRIMARY_DOCUMENT_ID,
    actorId: String = "local_user",
) {
    val stateHolder = rememberEditorState()
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    val theme = rememberCascadeTheme()
    val document by session.document.collectAsState()
    val storedJson = remember(document) { session.documents().firstOrNull { it.id == documentId }?.json }
    // What the session last held for this document, verbatim, and the editor's own encoding of it.
    var lastStoredJson by remember(session.canvasId, documentId) { mutableStateOf<String?>(null) }
    var lastEditorJson by remember(session.canvasId, documentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(storedJson) {
        if (storedJson != null && storedJson != lastStoredJson) {
            runCatching { stateHolder.loadFromJson(storedJson, textStates, spanStates) }
            lastStoredJson = storedJson
        }
        if (lastEditorJson == null) {
            lastEditorJson = runCatching { stateHolder.toJson(textStates, spanStates) }.getOrNull()
        }
    }

    LaunchedEffect(session.canvasId, documentId) {
        while (isActive) {
            delay(PERSIST_INTERVAL_MS)
            val current = runCatching { stateHolder.toJson(textStates, spanStates) }.getOrNull() ?: continue
            if (current == lastEditorJson) continue
            lastEditorJson = current
            lastStoredJson = current
            runCatching { session.setDocument(documentId, current, actorId) }
        }
    }

    CascadeEditor(
        stateHolder = stateHolder,
        textStates = textStates,
        spanStates = spanStates,
        theme = theme,
        modifier = modifier,
        toolbar = ToolbarSlot.Default(),
        config = CascadeEditorConfig(emptyDocumentPlaceholderEnabled = true),
    )
}

/**
 * A small read-only card of the canvas's notes for the drawing view; tapping it opens the editor.
 * Nothing is drawn while the canvas has no notes.
 */
@OptIn(ExperimentalCascadePreviewApi::class)
@Composable
fun CanvasNotesPreviewCard(
    session: CanvasSession,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    documentId: String = CANVAS_PRIMARY_DOCUMENT_ID,
) {
    val document by session.document.collectAsState()
    val json = remember(document) { session.documents().firstOrNull { it.id == documentId }?.json } ?: return
    val holder: EditorStateHolder = rememberEditorState()
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    var loadedJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(json) {
        if (json != loadedJson) {
            runCatching { holder.loadFromJson(json, textStates, spanStates) }
            loadedJson = json
        }
    }
    val theme = rememberCascadeTheme()
    Surface(
        modifier = modifier.width(280.dp).clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CascadeDocumentPreview(
                blocks = holder.state.blocks,
                theme = theme,
                config = CascadeDocumentPreviewConfig.Default.copy(maxBlocks = PREVIEW_MAX_BLOCKS, textScale = PREVIEW_TEXT_SCALE),
            )
        }
    }
}

@Composable
private fun rememberCascadeTheme(): CascadeEditorTheme {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE_THRESHOLD
    return remember(dark) { if (dark) CascadeEditorTheme.dark() else CascadeEditorTheme.light() }
}

private const val PERSIST_INTERVAL_MS = 750L
private const val PREVIEW_MAX_BLOCKS = 6
private const val PREVIEW_TEXT_SCALE = 0.85f
private const val DARK_LUMINANCE_THRESHOLD = 0.5f
