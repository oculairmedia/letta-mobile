package com.letta.mobile.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.data.model.MessageContentPart

internal data class WebImageAttachments(
    val images: List<MessageContentPart.Image>,
    val onAttach: () -> Unit,
    val onRemove: (Int) -> Unit,
)

@Composable
internal fun WebChatComposer(
    input: String,
    selectedAgent: AgentItemState?,
    enabled: Boolean,
    compact: Boolean,
    workspaceName: String?,
    attachments: WebImageAttachments,
    onInputChanged: (String) -> Unit,
    onOpenWorkspace: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (compact) 16.dp else 28.dp,
            top = 4.dp,
            end = if (compact) 16.dp else 28.dp,
            bottom = if (compact) 12.dp else 20.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val composerWidth = minOf(maxWidth, 760.dp)
            Surface(
                modifier = Modifier.width(composerWidth),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (attachments.images.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachments.images.forEachIndexed { index, _ ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Image ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                    IconButton(onClick = { attachments.onRemove(index) }, modifier = Modifier.size(26.dp)) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Remove image ${index + 1}", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 120.dp),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (input.isEmpty()) {
                                Text(
                                    selectedAgent?.let { "Message ${it.name}…" } ?: "Select an agent",
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    WebTooltip("Attach images") {
                        IconButton(onClick = attachments.onAttach, enabled = enabled, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Attach images", modifier = Modifier.size(17.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    WebTooltip(if (workspaceName == null) "Open local workspace" else "Change workspace") {
                        IconButton(onClick = onOpenWorkspace, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "Open local workspace", modifier = Modifier.size(17.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(
                            workspaceName ?: selectedAgent?.model ?: "No agent selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    WebTooltip("Send message") {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = enabled && (input.isNotBlank() || attachments.images.isNotEmpty()),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", modifier = Modifier.size(17.dp))
                        }
                    }
                    }
                }
            }
        }
        if (!compact) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Attach images   ·   Enter to send   ·   Shift+Enter newline",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                    modifier = Modifier.width(minOf(maxWidth, 760.dp)).padding(start = 8.dp),
                )
            }
        }
    }
}
