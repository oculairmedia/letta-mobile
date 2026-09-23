package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ALargeSmall
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.SquarePlus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.X
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the opened note offers beyond its own text: the board's undo, copying and removing it. */
class NoteEditorActions(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onDuplicate: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/** Which of the foot bar's panels is showing: its icons, the colours, or the text formatting. */
private enum class NoteFoot { ICONS, COLOURS, FORMAT }

/**
 * A note opened large, laid out the way Google Keep lays out a note: back at the top, the title
 * above the body, and one bar at the foot that rides on the keyboard. The bar's icons open what
 * they name in its place — add a checklist, the colours, the text formatting — beside undo, redo
 * and the note's menu.
 *
 * It is a panel inside the board's own box rather than a platform dialog, so it behaves the same
 * on desktop and Android. On a phone it takes the whole board; on a wide board it floats as a
 * card. The card underneath shows a read-only preview while this is open, so only one editor
 * writes the document.
 */
@Composable
fun CanvasNoteEditorPanel(
    session: CanvasSession,
    document: CanvasSceneDocument,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onToolbar: ((NoteToolbar?) -> Unit)? = null,
    /** The whole overlay, scrim included, is chrome: nothing behind it is drawable. */
    chromeRegions: CanvasChromeRegions? = null,
    /** A phone: the note takes the whole board instead of floating as a card. */
    compact: Boolean = false,
    actions: NoteEditorActions? = null,
) {
    val tint = parseHexColor(document.color)?.takeIf { it.alpha > 0f }
    val background = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val onCard = if (tint != null) contrastOn(tint) else MaterialTheme.colorScheme.onSurface
    var toolbar by remember(document.id) { mutableStateOf<NoteToolbar?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .canvasChrome(chromeRegions)
            .background(if (compact) background else MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // A tap on the scrim closes; taps on the note stay in the note.
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Clear of the system bars, and of the keyboard, which the foot bar rides on.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (compact) Modifier else Modifier.padding(horizontal = LettaDimens.Space.xl, vertical = 56.dp).widthIn(max = 880.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .semantics { contentDescription = "Note editor" },
            shape = if (compact) RectangleShape else RoundedCornerShape(LettaDimens.Radius.lg),
            color = background,
            tonalElevation = if (compact) 0.dp else LettaDimens.Space.xs,
            shadowElevation = if (compact) 0.dp else LettaDimens.Space.md,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NoteIcon(Lucide.ArrowLeft, "Close note editor", onCard, onClick = onClose)
                }
                NoteTitleField(session, document, onCard, compact)
                CanvasBlockEditor(
                    session = session,
                    documentId = document.id,
                    storedJson = document.json,
                    active = true,
                    onLightSurface = tint != null,
                    onToolbar = { handed ->
                        toolbar = handed
                        onToolbar?.invoke(handed)
                    },
                    style = document.style,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .padding(horizontal = if (compact) LettaDimens.Space.md else LettaDimens.Space.xl, vertical = LettaDimens.Space.sm),
                )
                NoteFootBar(session, document, toolbar, actions, onCard, background)
            }
        }
    }
}

/** The title above the body: large, one line, saved a moment after typing stops. */
@Composable
private fun NoteTitleField(session: CanvasSession, document: CanvasSceneDocument, onCard: Color, compact: Boolean) {
    val recorder = LocalCanvasDocumentRecorder.current
    val latest by rememberUpdatedState(document)
    var title by remember(document.id) { mutableStateOf(document.title.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    // Renamed somewhere else: take it, unless the person is typing their own.
    LaunchedEffect(document.title) { if (!focused) title = document.title.orEmpty() }
    LaunchedEffect(title) {
        if (title == latest.title.orEmpty()) return@LaunchedEffect
        delay(TITLE_SAVE_DELAY_MS)
        recorder.recordingOrJust("renaming a note") {
            runCatching { session.retitleDocument(latest.id, title) }
        }
    }
    val style = MaterialTheme.typography.headlineSmall.copy(color = onCard, fontWeight = FontWeight.Medium)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) LettaDimens.Space.lg else LettaDimens.Space.xl + LettaDimens.Space.xs)) {
        if (title.isEmpty()) Text("Title", style = style.copy(color = onCard.copy(alpha = 0.5f)))
        BasicTextField(
            value = title,
            onValueChange = { title = it.replace('\n', ' ') },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(onCard),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Note title" },
        )
    }
}

@Composable
private fun NoteFootBar(
    session: CanvasSession,
    document: CanvasSceneDocument,
    toolbar: NoteToolbar?,
    actions: NoteEditorActions?,
    onCard: Color,
    background: Color,
) {
    var foot by remember(document.id) { mutableStateOf(NoteFoot.ICONS) }
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        if (foot == NoteFoot.COLOURS) NoteColourPanel(session, document, onCard)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (foot == NoteFoot.FORMAT && toolbar != null) {
                CanvasFormattingBar(toolbar = toolbar, modifier = Modifier.weight(1f))
                NoteIcon(Lucide.X, "Close formatting", onCard) { foot = NoteFoot.ICONS }
                return@Row
            }
            NoteAddMenu(toolbar, onCard)
            NoteIcon(Lucide.Palette, "Colours", onCard, selected = foot == NoteFoot.COLOURS) {
                foot = if (foot == NoteFoot.COLOURS) NoteFoot.ICONS else NoteFoot.COLOURS
            }
            NoteIcon(Lucide.ALargeSmall, "Text formatting", onCard, enabled = toolbar != null) { foot = NoteFoot.FORMAT }
            Spacer(modifier = Modifier.weight(1f))
            if (actions != null) {
                NoteIcon(Lucide.Undo2, "Undo", onCard, enabled = actions.canUndo, onClick = actions.onUndo)
                NoteIcon(Lucide.Redo2, "Redo", onCard, enabled = actions.canRedo, onClick = actions.onRedo)
                NoteMoreMenu(actions, onCard)
            }
        }
    }
}

/** The "+" menu: what can be added to the note. Checklists are what the editor has today. */
@Composable
private fun NoteAddMenu(toolbar: NoteToolbar?, onCard: Color) {
    var open by remember { mutableStateOf(false) }
    val todo = BlockButtons.firstOrNull { it.label == "To-do" }
    Box {
        NoteIcon(Lucide.SquarePlus, "Add to note", onCard, enabled = toolbar != null && todo != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (toolbar != null && todo != null) {
                MenuRow(Lucide.ListTodo, "Checkboxes") {
                    open = false
                    toolbar.apply(todo)
                }
            }
        }
    }
}

@Composable
private fun NoteMoreMenu(actions: NoteEditorActions, onCard: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        NoteIcon(Lucide.EllipsisVertical, "More", onCard) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.onDuplicate?.let { duplicate ->
                MenuRow(Lucide.Copy, "Make a copy") {
                    open = false
                    duplicate()
                }
            }
            MenuRow(Lucide.Trash2, "Delete") {
                open = false
                actions.onDelete()
            }
        }
    }
}

/** Keep's colour sheet, in the bar's place: the note's colour, then its text's. */
@Composable
private fun NoteColourPanel(session: CanvasSession, document: CanvasSceneDocument, onCard: Color) {
    val scope = rememberCoroutineScope()
    val recorder = LocalCanvasDocumentRecorder.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text("Color", style = MaterialTheme.typography.titleSmall, color = onCard)
        SwatchRow(NoteColors, current = parseHexColor(document.color)) { picked ->
            scope.launch {
                recorder.recordingOrJust("recolouring a note") { runCatching { session.recolorDocument(document.id, picked.toHex()) } }
            }
        }
        Text("Text", style = MaterialTheme.typography.titleSmall, color = onCard)
        SwatchRow(StrokePalette, current = parseHexColor(document.style?.textColor)) { picked ->
            scope.launch {
                recorder.recordingOrJust("recolouring the text") {
                    val style = document.style ?: CanvasTextStyle()
                    runCatching { session.restyleDocument(document.id, style.copy(textColor = picked.toHex())) }
                }
            }
        }
    }
}

@Composable
private fun SwatchRow(palette: List<NamedColor>, current: Color?, onPick: (Color) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        palette.forEach { swatch ->
            val selected = current != null && swatch.color.toHex() == current.toHex()
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(swatch.color)
                    .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(swatch.color) }
                    .semantics { contentDescription = swatch.name },
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
        onClick = onClick,
    )
}

@Composable
private fun NoteIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(LettaDimens.Orb.lg)
            .then(if (selected) Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f)) else Modifier)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

private val SWATCH = 40.dp
private const val TITLE_SAVE_DELAY_MS = 400L
