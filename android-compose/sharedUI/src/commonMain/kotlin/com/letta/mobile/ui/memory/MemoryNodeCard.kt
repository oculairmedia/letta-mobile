package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.graph.MemoryNodeContent
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.components.LettaSwatch
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.text.LettaSelectionContainer
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The selected node's inspector: identity, then what is useful about this kind of node (the
 * agent's context window and blocks, a block's size against its limit, a schedule's cron and next
 * run, a channel's status and address), then its contents. Blocks load their full value and, when
 * the backend can commit a write, offer Edit, which swaps the body for [MemoryNodeEditorPane].
 */
@Composable
internal fun MemoryNodeCardContent(
    card: MemoryNodeCardState,
    actions: MemoryPageActions,
    modifier: Modifier = Modifier,
) {
    val selection = card.selection
    val palette = rememberMemoryGraphPalette()
    Column(
        modifier = modifier.padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        MemoryNodeCardHeader(card, onClose = actions::clearSelection)
        MemoryNodeKindFacts(card, palette, actions::selectNode)
        val editor = selection.editor
        when {
            editor != null -> MemoryNodeEditorPane(selection, editor, actions)
            selection.detail.kind.isRoot() -> Unit
            else -> MemoryNodeBody(card, actions)
        }
    }
}

@Composable
private fun ColumnScope.MemoryNodeKindFacts(card: MemoryNodeCardState, palette: MemoryGraphPalette, onOpen: (String) -> Unit) {
    when (val item = card.item) {
        is MemoryParityItem.Schedule -> MemoryScheduleFacts(item)
        is MemoryParityItem.Channel -> MemoryChannelFacts(item, palette)
        is MemoryParityItem.Skill -> MemorySkillFacts(item)
        is MemoryParityItem.MemoryBlock -> MemoryBlockFacts(card)
        null -> if (card.selection.detail.kind.isRoot()) {
            // The agent's lists can outgrow a phone's bottom sheet: they scroll inside it.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                MemoryAgentFacts(card, palette, onOpen)
            }
        }
    }
}

private fun MemoryGraphNodeKind.isRoot(): Boolean = this == MemoryGraphNodeKind.Agent || this == MemoryGraphNodeKind.Backend

@Composable
private fun MemoryNodeCardHeader(card: MemoryNodeCardState, onClose: () -> Unit) {
    val detail = card.selection.detail
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                LettaSwatch(card.accent)
                Text(
                    kindOverline(detail.kind).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = card.accent,
                )
            }
            Text(
                detail.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            card.meaningfulSubtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(LettaIcons.Close, contentDescription = "Close")
        }
    }
}

private fun kindOverline(kind: MemoryGraphNodeKind): String = when (kind) {
    MemoryGraphNodeKind.Memory -> "Memory block"
    else -> memoryNodeKindLabel(kind)
}

/** The node's text (a block's full value, a schedule's message), then edit/delete for a writable block. */
@Composable
private fun ColumnScope.MemoryNodeBody(card: MemoryNodeCardState, actions: MemoryPageActions) {
    val selection = card.selection
    when (val content = selection.content) {
        MemoryNodeContent.Loading -> MemoryNodeLoading()
        is MemoryNodeContent.Failed -> MemoryNodeLoadFailed(content.message, actions::retryContent)
        else -> MemoryNodeValue(card, monospace = content is MemoryNodeContent.Loaded)
    }
    if (selection.canEdit || selection.canDelete) MemoryNodeActions(card, actions)
}

/** The node's text, under a "Value" heading for a block; schedules and channels say it all in their facts. */
@Composable
private fun ColumnScope.MemoryNodeValue(card: MemoryNodeCardState, monospace: Boolean) {
    if (card.item is MemoryParityItem.Schedule || card.item is MemoryParityItem.Channel) return
    if (card.item is MemoryParityItem.MemoryBlock) LettaSectionLabel("Value")
    MemoryNodeText(card.selection.displayText, monospace = monospace)
}

@Composable
private fun MemoryNodeActions(card: MemoryNodeCardState, actions: MemoryPageActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (card.selection.canDelete) {
            MemoryCardAction(LettaIcons.Delete, "Delete", actions::requestDelete, MaterialTheme.colorScheme.error)
        }
        if (card.selection.canEdit) {
            MemoryCardAction(LettaIcons.Edit, "Edit", actions::beginEdit)
        }
    }
}

@Composable
private fun MemoryCardAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.Unspecified) {
    TextButton(onClick = onClick) {
        val color = tint.takeOrElse { LocalContentColor.current }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.icon))
        Spacer(Modifier.size(LettaDimens.Space.sm))
        Text(label, color = color)
    }
}

@Composable
private fun ColumnScope.MemoryNodeText(text: String, monospace: Boolean) {
    val shown = text.ifBlank { "(empty)" }
    LettaSelectionContainer(Modifier.weight(1f, fill = false)) {
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun MemoryNodeLoading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(LettaDimens.Control.icon), strokeWidth = LettaDimens.Stroke.hairline * 2)
        Text("Loading block…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemoryNodeLoadFailed(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}
