package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.icons.LettaIcons

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
