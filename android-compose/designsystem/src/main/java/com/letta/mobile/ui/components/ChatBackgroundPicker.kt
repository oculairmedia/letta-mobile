package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.icons.LettaIcons

@Composable
fun ChatBackgroundPicker(
    selected: ChatBackground?,
    onSelect: (ChatBackground) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = remember { ChatBackground.allPresets }
    val selectedKey = selected?.key ?: "default"

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Chat Background",
            style = MaterialTheme.typography.labelLarge,
        )
        // Fixed grid: rows of 5 items
        val rows = presets.chunked(5)
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in row) {
                        val key = preset.key
                        BackgroundSwatch(
                            background = preset,
                            isSelected = key == selectedKey,
                            onClick = { onSelect(preset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSwatch(
    background: ChatBackground,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .then(
                when (background) {
                    is ChatBackground.Default -> Modifier.background(MaterialTheme.colorScheme.surface, shape)
                    is ChatBackground.SolidColor -> Modifier.background(background.color, shape)
                    is ChatBackground.Gradient -> Modifier.background(background.toBrush(), shape)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape)
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LettaIcons.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (background is ChatBackground.Default) {
            if (!isSelected) {
                Text(
                    text = "∅",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
