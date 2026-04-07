package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MessageSender(
    isUser: Boolean,
    agentName: String = "",
    modifier: Modifier = Modifier,
) {
    val label = if (isUser) "You" else agentName.ifBlank { "Agent" }
    val alignment = if (isUser) TextAlign.End else TextAlign.Start

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = alignment,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isUser) 52.dp else 4.dp, vertical = 2.dp)
    )
}
