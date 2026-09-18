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
import com.composables.icons.lucide.Minimize2
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
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
) {
    val scope = rememberCoroutineScope()
    val tint = parseHexColor(document.color)?.takeIf { it.alpha > 0f }
    Box(
        modifier = modifier
            .fillMaxSize()
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
                    ColorSwatchPicker(
                        current = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                        palette = NoteColors,
                        label = "Note color",
                        onPick = { picked ->
                            scope.launch { runCatching { session.recolorDocument(document.id, picked.toHex()) } }
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
