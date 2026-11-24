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
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.customColors

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
                        Text("Hello! How are you?", color = MaterialTheme.colorScheme.onSurface)
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
