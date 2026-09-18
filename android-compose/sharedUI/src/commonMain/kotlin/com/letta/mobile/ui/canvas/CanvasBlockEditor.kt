package com.letta.mobile.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasTextStyle
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
import kotlinx.coroutines.launch

/**
 * One block document of the canvas, edited with the Cascade block editor and persisted through
 * the canvas op log as `set_document` ops, so peers and the agent see the same text.
 *
 * [storedJson] is what the session currently holds for the document. Blank means a note that was
 * just placed and has never been written, so the editor starts with one empty paragraph to type
 * into rather than failing to parse.
 *
 * [active] is the editor the person is working in: it enables block selection and dragging and
 * hands its formatting controls up through [onToolbar] so the host can draw them where there is
 * room (the foot of the board) instead of inside a small card. An inactive one is plain text so
 * a board of notes stays quiet.
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
    /** Receives the editor's formatting controls while active, null again when it leaves. */
    onToolbar: ((NoteToolbar?) -> Unit)? = null,
    /** How this document's text is set: size, family, colour, alignment. */
    style: CanvasTextStyle? = null,
) {
    val stateHolder = rememberEditorState(initialBlocks = listOf(Block.paragraph("")))
    val textStates = remember { BlockTextStates() }
    val spanStates = remember { BlockSpanStates() }
    val theme = rememberCascadeTheme(forceLight = onLightSurface, style = style)
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

    // Writes the editor's document to the session when it differs from what was last written.
    suspend fun persist() {
        val current = runCatching { stateHolder.toJson(textStates, spanStates) }.getOrNull() ?: return
        if (current == lastEditorJson) return
        lastEditorJson = current
        lastStoredJson = current
        runCatching { session.setDocument(documentId, current, actorId) }
    }

    LaunchedEffect(session.canvasId, documentId) {
        while (isActive) {
            delay(PERSIST_INTERVAL_MS)
            persist()
        }
    }
    val scope = rememberCoroutineScope()

    if (onToolbar != null) {
        DisposableEffect(active) { onDispose { onToolbar(null) } }
    }
    val toolbar: ToolbarSlot = when {
        !active -> ToolbarSlot.None
        onToolbar != null -> ToolbarSlot.Custom { formatting, actions ->
            // Rendered by the host; the slot only reports what it was handed.
            SideEffect {
                onToolbar(
                    NoteToolbar(formatting, actions, stateHolder, textStates, spanStates) {
                        // A block change from the bar is a deliberate edit: save it now, not on the tick.
                        scope.launch { persist() }
                    },
                )
            }
        }
        else -> ToolbarSlot.Default()
    }
    CascadeEditor(
        stateHolder = stateHolder,
        textStates = textStates,
        spanStates = spanStates,
        registry = rememberCanvasBlockRegistry(),
        theme = theme,
        modifier = modifier,
        toolbar = toolbar,
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
    style: CanvasTextStyle? = null,
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
        registry = rememberCanvasBlockRegistry(),
        theme = rememberCascadeTheme(forceLight = onLightSurface, style = style),
        config = CascadeDocumentPreviewConfig.Default,
    )
}

@Composable
internal fun rememberCascadeTheme(forceLight: Boolean = false, style: CanvasTextStyle? = null): CascadeEditorTheme {
    val dark = !forceLight && MaterialTheme.colorScheme.background.luminance() < DARK_LUMINANCE_THRESHOLD
    return remember(dark, style) {
        val base = if (dark) CascadeEditorTheme.dark() else CascadeEditorTheme.light()
        if (style == null) base else base.applyStyle(style)
    }
}

/** The document's [CanvasTextStyle] laid over a base theme: sizes scaled, family, colour, alignment. */
internal fun CascadeEditorTheme.applyStyle(style: CanvasTextStyle): CascadeEditorTheme {
    val scale = style.fontScale ?: 1f
    val family = when (style.fontFamily) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "sans" -> FontFamily.SansSerif
        else -> null
    }
    val align = when (style.align) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        "start" -> TextAlign.Start
        else -> null
    }
    val color = parseHexColor(style.textColor)
    fun androidx.compose.ui.text.TextStyle.styled(): androidx.compose.ui.text.TextStyle = copy(
        fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
        fontFamily = family ?: fontFamily,
        textAlign = align ?: textAlign,
    )
    val t = typography
    return copy(
        typography = t.copy(
            body = t.body.styled(),
            heading1 = t.heading1.styled(),
            heading2 = t.heading2.styled(),
            heading3 = t.heading3.styled(),
            heading4 = t.heading4.styled(),
            heading5 = t.heading5.styled(),
            heading6 = t.heading6.styled(),
            code = t.code.styled(),
        ),
        colors = if (color != null) colors.copy(text = color, cursor = color) else colors,
    )
}

private const val PERSIST_INTERVAL_MS = 750L
private const val DARK_LUMINANCE_THRESHOLD = 0.5f
