package com.letta.mobile.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.letta.mobile.data.canvas.CanvasSession
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.serialization.loadFromJson
import io.github.linreal.cascade.editor.serialization.toJson
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
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
 * One block document of the canvas, edited with the Cascade block editor and persisted through
 * the canvas op log as `set_document` ops, so peers and the agent see the same text.
 *
 * [storedJson] is what the session currently holds for the document. Blank means a note that was
 * just placed and has never been written, so the editor starts with one empty paragraph to type
 * into rather than failing to parse.
 *
 * [active] is the editor the person is working in: it shows the rich-text toolbar and enables
 * block selection and dragging. An inactive one is plain text so a board of notes stays quiet.
 *
 * Persistence is commit-based: the editor's JSON is compared to what the session holds on a short
 * cadence and written only when it changed, which coalesces typing into one op per pause. A newer
 * document arriving through the session (another peer, the agent, a checkpoint restore) reloads
 * the editor.
 */
@Composable
fun CanvasBlockEditor(
    session: CanvasSession,
    documentId: String,
    storedJson: String,
    modifier: Modifier = Modifier,
    actorId: String = "local_user",
    active: Boolean = true,
    /** True when the editor sits on a pale tint (a coloured note), so its text stays dark. */
    onLightSurface: Boolean = false,
) {
    val stateHolder = rememberEditorState(initialBlocks = listOf(Block.paragraph("")))
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    val theme = rememberCascadeTheme(forceLight = onLightSurface)
    // What the session last held for this document, verbatim, and the editor's own encoding of it.
    var lastStoredJson by remember(session.canvasId, documentId) { mutableStateOf<String?>(null) }
    var lastEditorJson by remember(session.canvasId, documentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(storedJson) {
        if (storedJson.isNotBlank() && storedJson != lastStoredJson) {
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
        toolbar = if (active) ToolbarSlot.Default() else ToolbarSlot.None,
        config = CascadeEditorConfig(
            blockSelectionEnabled = active,
            blockDraggingEnabled = active,
            emptyDocumentPlaceholderEnabled = true,
        ),
    )
}

/**
 * A read-only rendering of a block document, for a note card whose text is being edited
 * elsewhere (the expanded editor) so two editors never write the same document.
 */
@OptIn(ExperimentalCascadePreviewApi::class)
@Composable
fun CanvasBlockPreview(
    json: String,
    modifier: Modifier = Modifier,
    onLightSurface: Boolean = false,
) {
    val holder = rememberEditorState(initialBlocks = listOf(Block.paragraph("")))
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    var loadedJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(json) {
        if (json.isNotBlank() && json != loadedJson) {
            runCatching { holder.loadFromJson(json, textStates, spanStates) }
            loadedJson = json
        }
    }
    CascadeDocumentPreview(
        blocks = holder.state.blocks,
        modifier = modifier,
        theme = rememberCascadeTheme(forceLight = onLightSurface),
        config = CascadeDocumentPreviewConfig.Default,
    )
}

@Composable
internal fun rememberCascadeTheme(forceLight: Boolean = false): CascadeEditorTheme {
    val dark = !forceLight && MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE_THRESHOLD
    return remember(dark) { if (dark) CascadeEditorTheme.dark() else CascadeEditorTheme.light() }
}

private const val PERSIST_INTERVAL_MS = 750L
private const val DARK_LUMINANCE_THRESHOLD = 0.5f
