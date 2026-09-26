package com.letta.mobile.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.memory.graph.MemoryNodeContent
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** What the node card renders: the selection plus graph-derived decoration. */
@Immutable
internal data class MemoryNodeCardState(
    val selection: MemoryNodeSelection,
    val degree: Int,
    val accent: Color,
)

/**
 * The selected node's card: identity, stats, and its full contents. Memory
 * blocks load their complete value and, when the backend can commit a write,
 * offer Edit, which swaps the body for [MemoryNodeEditorPane].
 */
@Composable
internal fun MemoryNodeCardContent(
    card: MemoryNodeCardState,
    actions: MemoryPageActions,
    modifier: Modifier = Modifier,
) {
    val selection = card.selection
    Column(
        modifier = modifier.padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        MemoryNodeCardHeader(selection, card.accent, onClose = actions::clearSelection)
        MemoryNodeStats(selection, card.degree)
        val editor = selection.editor
        if (editor != null) {
            MemoryNodeEditorPane(selection, editor, actions)
        } else {
            MemoryNodeBody(selection, actions)
        }
    }
}

@Composable
private fun MemoryNodeCardHeader(selection: MemoryNodeSelection, accent: Color, onClose: () -> Unit) {
    val detail = selection.detail
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Box(Modifier.size(LettaDimens.Space.md).clip(CircleShape).background(accent))
        Column(Modifier.weight(1f)) {
            Text(detail.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(LettaIcons.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun MemoryNodeStats(selection: MemoryNodeSelection, degree: Int) {
    val detail = selection.detail
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
        MemoryNodeStat("Type", memoryNodeKindLabel(detail.kind))
        MemoryNodeStat("Links", degree.toString())
        MemoryNodeStat("Chars", selection.displayText.length.toString())
        detail.limit?.let { MemoryNodeStat("Limit", it.toString()) }
        if (detail.readOnly) MemoryNodeStat("Access", "Read-only")
    }
}

@Composable
private fun MemoryNodeStat(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.onSurfaceMutedColor)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ColumnScope.MemoryNodeBody(selection: MemoryNodeSelection, actions: MemoryPageActions) {
    when (val content = selection.content) {
        MemoryNodeContent.Loading -> MemoryNodeLoading()
        is MemoryNodeContent.Failed -> MemoryNodeLoadFailed(content.message, actions::retryContent)
        else -> MemoryNodeText(selection.displayText, monospace = content is MemoryNodeContent.Loaded)
    }
    selection.detail.metadataLabels.takeIf { it.isNotEmpty() }?.let { labels ->
        Text(labels.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (selection.canEdit || selection.canDelete) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (selection.canDelete) {
                MemoryCardAction(LettaIcons.Delete, "Delete", actions::requestDelete, MaterialTheme.colorScheme.error)
            }
            if (selection.canEdit) {
                MemoryCardAction(LettaIcons.Edit, "Edit", actions::beginEdit)
            }
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
    SelectionContainer(Modifier.weight(1f, fill = false)) {
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
