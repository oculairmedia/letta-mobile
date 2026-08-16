package com.letta.mobile.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
internal data class DesktopConversationTab(
    val conversationId: String,
    val title: String,
    val agentName: String,
)

@Composable
internal fun DesktopConversationTabRow(
    tabs: List<DesktopConversationTab>,
    activeConversationId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            DesktopConversationTabItem(
                tab = tab,
                active = tab.conversationId == activeConversationId,
                onSelect = { onSelect(tab.conversationId) },
                onClose = { onClose(tab.conversationId) },
            )
        }
    }
}

@Composable
private fun DesktopConversationTabItem(
    tab: DesktopConversationTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember(tab.conversationId) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 132.dp, max = 220.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(7.dp),
        color = if (active) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (active) 2.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Box {
            DesktopConversationTabLabel(
                tab = tab,
                active = active,
                modifier = Modifier.align(Alignment.Center),
            )
            if (hovered || active) {
                DesktopConversationTabCloseButton(
                    title = tab.title,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun DesktopConversationTabLabel(
    tab: DesktopConversationTab,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = tab.agentName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DesktopConversationTabCloseButton(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClose,
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close $title tab",
            modifier = Modifier.size(14.dp),
        )
    }
}
