package com.letta.mobile.ui.chat.surface.composer

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
import androidx.compose.material3.MaterialTheme
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
import com.composables.icons.lucide.ChartPie
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.context.ContextWindowSegment
import com.letta.mobile.data.context.ContextWindowSegmentKind
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.context.formatContextPercent
import com.letta.mobile.data.context.formatContextShare
import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_context_empty
import com.letta.mobile.sharedui.resources.composer_context_label
import com.letta.mobile.sharedui.resources.composer_context_loading_value
import com.letta.mobile.sharedui.resources.composer_context_measuring
import com.letta.mobile.sharedui.resources.composer_context_title
import com.letta.mobile.sharedui.resources.composer_context_total
import com.letta.mobile.sharedui.resources.composer_context_total_share
import com.letta.mobile.sharedui.resources.composer_context_unknown_total
import com.letta.mobile.sharedui.resources.composer_context_unknown_value
import com.letta.mobile.ui.theme.ChatComposerColors
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The composer's context-window chip and breakdown popover: how much of the agent's window
 * this conversation occupies, by the sections the server itemises, with the rest as free
 * space. Lifted from desktop's DesktopComposerContextUsage.
 */
@Composable
internal fun ComposerContextChip(state: ContextWindowUsageState) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComposerActionChip(
            label = ComposerChipLabel(
                text = stringResource(Res.string.composer_context_label, contextChipValue(state)),
                leadingIcon = Lucide.ChartPie,
            ),
            onClick = { open = !open },
        )
        if (open) {
            ComposerPopover(
                width = ChatComposerDimens.contextPopoverWidth,
                onDismiss = { open = false },
                testTag = ComposerTestTags.CONTEXT_POPOVER,
            ) {
                ContextUsageBody(state)
            }
        }
    }
}

@Composable
private fun contextChipValue(state: ContextWindowUsageState): String {
    val usage = state.usage
    return when {
        usage != null && usage.maxTokens > 0 -> formatContextPercent(usage.usedFraction)
        // A total with no known window: show the count rather than a share of nothing.
        usage != null && usage.usedTokens > 0 -> formatContextTokens(usage.usedTokens)
        state.loading -> stringResource(Res.string.composer_context_loading_value)
        else -> stringResource(Res.string.composer_context_unknown_value)
    }
}

@Composable
private fun ContextUsageBody(state: ContextWindowUsageState) {
    Column(modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs)) {
        ContextUsageHeader(state.usage)
        val usage = state.usage
        if (usage == null) {
            ContextUsagePlaceholder(state)
        } else {
            // No window, no scale: the bar would only be invented, so the rows stand alone.
            if (usage.maxTokens > 0) {
                Box(modifier = Modifier.height(LettaDimens.Space.md))
                ContextUsageBar(usage)
            }
            Box(modifier = Modifier.height(LettaDimens.Space.md))
            ContextUsageRows(usage)
        }
    }
}

@Composable
private fun ContextUsageHeader(usage: ContextWindowUsage?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.composer_context_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (usage != null) {
            Text(
                text = contextTotalText(usage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun contextTotalText(usage: ContextWindowUsage): String {
    val used = formatContextTokens(usage.usedTokens)
    return if (usage.maxTokens > 0) {
        stringResource(
            Res.string.composer_context_total_share,
            used,
            formatContextTokens(usage.maxTokens),
            formatContextPercent(usage.usedFraction),
        )
    } else {
        stringResource(Res.string.composer_context_total, used, stringResource(Res.string.composer_context_unknown_total))
    }
}

@Composable
private fun ContextUsagePlaceholder(state: ContextWindowUsageState) {
    val message = when {
        state.loading -> stringResource(Res.string.composer_context_measuring)
        else -> state.error ?: stringResource(Res.string.composer_context_empty)
    }
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LettaDimens.Space.sm),
    )
}

@Composable
private fun ContextUsageBar(usage: ContextWindowUsage) {
    // Weighted stripes: a segment under ~1% still gets a sliver so the bar accounts for every row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LettaDimens.Space.sm)
            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Stroke.hairline),
    ) {
        usage.segments.forEach { segment ->
            ContextBarStripe(segment.fraction, contextSegmentColor(segment.kind))
        }
        if (usage.freeSegment.tokens > 0) {
            ContextBarStripe(usage.freeSegment.fraction, contextSegmentColor(ContextWindowSegmentKind.FreeSpace))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ContextBarStripe(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .weight(fraction.coerceAtLeast(MinimumBarWeight))
            .height(LettaDimens.Space.sm)
            .background(color),
    )
}

@Composable
private fun ContextUsageRows(usage: ContextWindowUsage) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        usage.segments.forEach { segment -> ContextUsageRow(segment) }
        if (usage.maxTokens > 0) ContextUsageRow(usage.freeSegment)
    }
}

@Composable
private fun ContextUsageRow(segment: ContextWindowSegment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LettaDimens.Control.iconSm)
                .clip(RoundedCornerShape(LettaDimens.Radius.sm))
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
            modifier = Modifier.width(ChatComposerDimens.contextShareColumn),
        )
    }
}

@Composable
private fun contextSegmentColor(kind: ContextWindowSegmentKind): Color = when (kind) {
    ContextWindowSegmentKind.System -> ChatComposerColors.contextSystem
    ContextWindowSegmentKind.ToolDefinitions -> ChatComposerColors.contextToolDefinitions
    ContextWindowSegmentKind.ToolRules -> ChatComposerColors.contextToolRules
    ContextWindowSegmentKind.CoreMemory -> ChatComposerColors.contextCoreMemory
    ContextWindowSegmentKind.MemoryFiles -> ChatComposerColors.contextMemoryFiles
    ContextWindowSegmentKind.Directories -> ChatComposerColors.contextDirectories
    ContextWindowSegmentKind.SummaryMemory -> ChatComposerColors.contextSummaryMemory
    ContextWindowSegmentKind.ExternalMemorySummary -> ChatComposerColors.contextExternalSummary
    ContextWindowSegmentKind.Messages -> ChatComposerColors.contextMessages
    ContextWindowSegmentKind.Unitemised -> ChatComposerColors.contextUnitemised
    ContextWindowSegmentKind.FreeSpace -> MaterialTheme.colorScheme.surfaceContainerHighest
}

/** Keeps sub-percent sections visible in the bar instead of collapsing to nothing. */
private const val MinimumBarWeight = 0.004f
