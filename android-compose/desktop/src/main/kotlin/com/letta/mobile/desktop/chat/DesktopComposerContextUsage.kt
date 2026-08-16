package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.context.ContextWindowSegment
import com.letta.mobile.data.context.ContextWindowSegmentKind
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.formatContextPercent
import com.letta.mobile.data.context.formatContextShare
import com.letta.mobile.data.context.formatContextTokens

/** What the composer knows about the focused conversation's context window. */
internal data class ComposerContextUsageState(
    val usage: ContextWindowUsage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Composer chip + popover for the context window: how much of the focused
 * agent's window this conversation currently occupies, broken down by the
 * sections the server itemises (system prompt, tool definitions, memory,
 * messages) with the remainder as free space.
 */
@Composable
internal fun ComposerContextChip(state: ComposerContextUsageState) {
    var open by remember { mutableStateOf(false) }
    val usage = state.usage
    val label = when {
        usage != null && usage.maxTokens > 0 -> formatContextPercent(usage.usedFraction)
        state.loading -> "…"
        else -> "—"
    }
    Box {
        ComposerActionChip(
            label = "Context $label",
            onClick = { open = !open },
            leadingIcon = Icons.Outlined.DataUsage,
        )
        if (open) {
            ContextUsagePopover(state = state, onDismiss = { open = false })
        }
    }
}

@Composable
private fun ContextUsagePopover(
    state: ComposerContextUsageState,
    onDismiss: () -> Unit,
) {
    Popup(
        popupPositionProvider = ViewportClampedPopupPositionProvider(yOffsetPx = -6),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(330.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                ContextUsageHeader(state.usage)
                val usage = state.usage
                if (usage == null) {
                    ContextUsagePlaceholder(state)
                } else {
                    Box(modifier = Modifier.height(10.dp))
                    ContextUsageBar(usage)
                    Box(modifier = Modifier.height(10.dp))
                    ContextUsageRows(usage)
                }
            }
        }
    }
}

@Composable
private fun ContextUsageHeader(usage: ContextWindowUsage?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Context window",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (usage != null) {
            val total = if (usage.maxTokens > 0) formatContextTokens(usage.maxTokens) else "?"
            val share = if (usage.maxTokens > 0) " (${formatContextPercent(usage.usedFraction)})" else ""
            Text(
                text = "${formatContextTokens(usage.usedTokens)} / $total$share",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContextUsagePlaceholder(state: ComposerContextUsageState) {
    val message = when {
        state.loading -> "Measuring…"
        state.error != null -> state.error
        else -> "No context reading for this conversation yet."
    }
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ContextUsageBar(usage: ContextWindowUsage) {
    // Weighted stripes rather than fixed widths: a segment under ~1% still gets
    // a hairline so the bar keeps accounting for every section it lists.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        usage.segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight(segment.fraction.coerceAtLeast(MinimumBarWeight))
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(contextSegmentColor(segment.kind)),
            )
        }
        if (usage.freeSegment.tokens > 0) {
            Box(
                modifier = Modifier
                    .weight(usage.freeSegment.fraction.coerceAtLeast(MinimumBarWeight))
                    .height(8.dp)
                    .background(contextSegmentColor(ContextWindowSegmentKind.FreeSpace)),
            )
        }
    }
}

@Composable
private fun ContextUsageRows(usage: ContextWindowUsage) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        usage.segments.forEach { segment -> ContextUsageRow(segment) }
        if (usage.maxTokens > 0) {
            ContextUsageRow(usage.freeSegment)
        }
    }
}

@Composable
private fun ContextUsageRow(segment: ContextWindowSegment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(contextSegmentColor(segment.kind)),
        )
        Text(
            text = segment.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatContextTokens(segment.tokens),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatContextShare(segment.fraction),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * Fixed hues rather than theme roles: the breakdown needs one distinguishable
 * colour per section, and the M3 scheme only offers a handful of accents. These
 * sit mid-saturation so they hold up on both the light and dark surfaces.
 */
@Composable
private fun contextSegmentColor(kind: ContextWindowSegmentKind): Color = when (kind) {
    ContextWindowSegmentKind.System -> Color(0xFFF0A030)
    ContextWindowSegmentKind.ToolDefinitions -> Color(0xFF4C8DFF)
    ContextWindowSegmentKind.ToolRules -> Color(0xFF7C6CF0)
    ContextWindowSegmentKind.CoreMemory -> Color(0xFF34C08A)
    ContextWindowSegmentKind.MemoryFiles -> Color(0xFF2FB0C7)
    ContextWindowSegmentKind.Directories -> Color(0xFFB07CF0)
    ContextWindowSegmentKind.SummaryMemory -> Color(0xFFE06CB0)
    ContextWindowSegmentKind.ExternalMemorySummary -> Color(0xFFD9534F)
    ContextWindowSegmentKind.Messages -> Color(0xFFE8622F)
    ContextWindowSegmentKind.FreeSpace -> MaterialTheme.colorScheme.surfaceContainerHighest
}

/** Keeps sub-percent sections visible in the bar instead of collapsing to nothing. */
private const val MinimumBarWeight = 0.004f
