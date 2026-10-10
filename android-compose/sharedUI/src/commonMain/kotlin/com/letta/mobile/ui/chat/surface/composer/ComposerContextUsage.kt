package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.ChartPie
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.context.formatContextPercent
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
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.context.ContextUsageBar
import com.letta.mobile.ui.context.ContextUsageRows
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
