package com.letta.mobile.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.context.ContextWindowSegment
import com.letta.mobile.data.context.ContextWindowSegmentKind
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.formatContextShare
import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.ui.theme.AgentContextDimens
import com.letta.mobile.ui.theme.ChatComposerColors
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens

/*
 * letta-mobile-3io8k: the context bar, its legend rows and the section colours, shared by the
 * composer's context popover and the drawer's context card and sheet (moved out of
 * ComposerContextUsage unchanged).
 */

/**
 * The stacked bar. [autoCompactAt] (a share of the window, letta-mobile-joigh) draws a tick where
 * letta-code auto-compacts, so the threshold reads against the sections that will reach it.
 */
@Composable
internal fun ContextUsageBar(usage: ContextWindowUsage, modifier: Modifier = Modifier, autoCompactAt: Float? = null) {
    Box(modifier.fillMaxWidth()) {
        ContextUsageStripes(usage)
        autoCompactAt?.takeIf { it in 0f..1f }?.let { share -> AutoCompactTick(share) }
    }
}

@Composable
private fun AutoCompactTick(share: Float) {
    Box(Modifier.fillMaxWidth(share).height(LettaDimens.Space.sm).testTag(AgentContextTags.AUTO_COMPACT_MARK)) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(AgentContextDimens.autoCompactMarkerWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
    }
}

@Composable
private fun ContextUsageStripes(usage: ContextWindowUsage) {
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
internal fun ContextUsageRows(usage: ContextWindowUsage) {
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
internal fun contextSegmentColor(kind: ContextWindowSegmentKind): Color = when (kind) {
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
