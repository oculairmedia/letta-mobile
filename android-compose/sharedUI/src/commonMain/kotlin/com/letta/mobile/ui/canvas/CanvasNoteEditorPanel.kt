package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Minimize2
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasTextStyle
import kotlinx.coroutines.launch
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A note opened large: the block editor over the board, the way Miro opens a doc from a sticky.
 * It is a panel inside the board's own box rather than a platform dialog, so it behaves the same
 * on desktop and Android and the host's chrome stays where it is. The card underneath shows a
 * read-only preview while this is open, so only one editor writes the document. Its formatting
 * controls go up through [onToolbar] like the card's, so the foot bar keeps working.
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
) {
    val scope = rememberCoroutineScope()
    val recorder = LocalCanvasDocumentRecorder.current
    val tint = parseHexColor(document.color)?.takeIf { it.alpha > 0f }
    Box(
        modifier = modifier
            .fillMaxSize()
            .canvasChrome(chromeRegions)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // A tap on the scrim closes; taps on the panel stay in the panel.
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LettaDimens.Space.xl, vertical = 56.dp)
                .widthIn(max = 880.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .semantics { contentDescription = "Note editor" },
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = LettaDimens.Space.xs,
            shadowElevation = LettaDimens.Space.md,
        ) {
            val onCard = if (tint != null) contrastOn(tint) else MaterialTheme.colorScheme.onSurface
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.xl, end = LettaDimens.Space.sm, top = LettaDimens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Note",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = onCard,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Text colour as well as card colour. Opened large, this panel is where a
                    // person does the rest of their writing, and the colour of the writing was
                    // the one property they had to close the note to reach.
                    ColorSwatchPicker(
                        current = parseHexColor(document.style?.textColor) ?: onCard,
                        palette = StrokePalette,
                        label = "Text color",
                        glyph = Lucide.Type,
                        onPick = { picked ->
                            scope.launch {
                                recorder.recordingOrJust("recolouring the text") {
                                    val style = document.style ?: CanvasTextStyle()
                                    runCatching {
                                        session.restyleDocument(document.id, style.copy(textColor = picked.toHex()))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(LettaDimens.Orb.lg),
                    )
                    ColorSwatchPicker(
                        current = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                        palette = NoteColors,
                        label = "Note color",
                        onPick = { picked ->
                            scope.launch {
                                recorder.recordingOrJust("recolouring a note") {
                                    runCatching { session.recolorDocument(document.id, picked.toHex()) }
                                }
                            }
                        },
                        modifier = Modifier.size(LettaDimens.Orb.lg),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(LettaDimens.Orb.lg)) {
                        Icon(Lucide.Minimize2, contentDescription = "Close note editor", modifier = Modifier.size(LettaDimens.Control.icon), tint = onCard)
                    }
                }
                CanvasBlockEditor(
                    session = session,
                    documentId = document.id,
                    storedJson = document.json,
                    active = true,
                    onLightSurface = tint != null,
                    onToolbar = onToolbar,
                    style = document.style,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.sm),
                )
            }
        }
    }
}
