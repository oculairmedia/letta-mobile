package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.icons.LettaIcons

@PreviewLightDark
@Composable
internal fun PreviewMessageBubbleContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(MessageBubbleShape(radius = 16.dp, isFromUser = true))
                            .background(MaterialTheme.customColors.userBubbleBgColor)
                            .padding(12.dp)
                    ) {
                        Text("Hello! How are you?", color = Color.White)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(MessageBubbleShape(radius = 16.dp, isFromUser = false))
                            .background(MaterialTheme.customColors.agentBubbleBgColor)
                            .padding(12.dp)
                    ) {
                        MarkdownText(text = "I'm doing **great**! How can I help you today?")
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun MessageBubblePreview() = PreviewMessageBubbleContent()

@PreviewLightDark
@Composable
internal fun PreviewThinkingSectionContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            ThinkingSection(
                thinkingText = "Let me analyze this step by step:\n1. First consideration\n2. Second point\n3. **Conclusion**: this approach works best.",
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ThinkingSectionPreview() = PreviewThinkingSectionContent()

@PreviewLightDark
@Composable
internal fun PreviewEmptyStateContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            EmptyState(
                icon = LettaIcons.ChatOutline,
                message = "Start a conversation",
                modifier = Modifier.height(200.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun EmptyStatePreview() = PreviewEmptyStateContent()

@PreviewLightDark
@Composable
internal fun PreviewMessageActionButtonsContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MessageActionButton(
                    label = "Copy",
                    icon = LettaIcons.Copy,
                    onClick = {},
                )
                MessageActionButton(
                    label = "Regenerate",
                    icon = LettaIcons.Refresh,
                    onClick = {},
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun MessageActionButtonsPreview() = PreviewMessageActionButtonsContent()

@PreviewLightDark
@Composable
internal fun PreviewLatencyTextContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LatencyText(latencyMs = 123f)
                LatencyText(latencyMs = 1234f)
                LatencyText(latencyMs = 65000f)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun LatencyTextPreview() = PreviewLatencyTextContent()

@PreviewLightDark
@Composable
internal fun PreviewErrorDialogContent() {
    LettaTheme(dynamicColor = false) {
        ErrorDialog(
            message = "Failed to connect to server. Please check your connection and try again.",
            onDismiss = {},
            onRetry = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun ErrorDialogPreview() = PreviewErrorDialogContent()

@PreviewLightDark
@Composable
internal fun PreviewRotationalLoaderContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Box(modifier = Modifier.padding(32.dp)) {
                RotationalLoader()
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun RotationalLoaderPreview() = PreviewRotationalLoaderContent()

@PreviewLightDark
@Composable
internal fun PreviewAccordionsContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column {
                Accordions(
                    title = "Memory Blocks",
                    subtitle = "2 blocks configured",
                    expanded = true,
                    onExpandedChange = {},
                ) {
                    Text(
                        text = "Persona block content here...",
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Accordions(
                    title = "System Prompt",
                    expanded = false,
                    onExpandedChange = {},
                ) {}
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun AccordionsPreview() = PreviewAccordionsContent()

@PreviewLightDark
@Composable
internal fun PreviewMessageSenderContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                MessageSender(isUser = false, agentName = "Letta Agent")
                Spacer(modifier = Modifier.height(8.dp))
                MessageSender(isUser = true)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun MessageSenderPreview() = PreviewMessageSenderContent()
