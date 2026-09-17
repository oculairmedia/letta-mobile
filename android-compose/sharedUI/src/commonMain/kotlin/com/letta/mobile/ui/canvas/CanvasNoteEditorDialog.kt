package com.letta.mobile.ui.canvas

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minimize2
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import kotlinx.coroutines.launch

/**
 * A note opened large: the block editor with its full toolbar in a dialog over the board, the way
 * Miro opens a doc from a sticky. The card underneath shows a read-only preview while this is
 * open, so only one editor writes the document.
 */
@Composable
fun CanvasNoteEditorDialog(
    session: CanvasSession,
    document: CanvasSceneDocument,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tint = parseHexColor(document.color)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .widthIn(max = 880.dp)
                .semantics { contentDescription = "Note editor" },
            shape = RoundedCornerShape(16.dp),
            color = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            val onCard = if (tint != null) contrastOn(tint) else MaterialTheme.colorScheme.onSurface
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
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
                            NoteColors.firstOrNull { it.color == picked }?.let { named ->
                                scope.launch { runCatching { session.recolorDocument(document.id, named.hex) } }
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                        Icon(Lucide.Minimize2, contentDescription = "Close note editor", modifier = Modifier.size(18.dp), tint = onCard)
                    }
                }
                CanvasBlockEditor(
                    session = session,
                    documentId = document.id,
                    storedJson = document.json,
                    active = true,
                    onLightSurface = tint != null,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}
