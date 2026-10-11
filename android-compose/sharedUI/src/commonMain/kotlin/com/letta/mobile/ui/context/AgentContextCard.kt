package com.letta.mobile.ui.context

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.ContextMeterLevel
import com.letta.mobile.data.context.formatContextTokens
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier
import com.letta.mobile.ui.components.ChevronIndication
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.modelcontrol.ModelControlStrings
import com.letta.mobile.ui.theme.AgentContextDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** letta-mobile-3io8k: test tags of the drawer context card and its sheet. */
object AgentContextTags {
    const val CARD = "agent-context-card"
    const val CARD_MODEL = "agent-context-card-model"
    const val CARD_USAGE = "agent-context-card-usage"
    const val SHEET = "agent-context-sheet"
    const val SCOPE = "agent-context-scope"
    const val PROVENANCE = "agent-context-provenance"
    const val TOTAL = "agent-context-total"
    const val COMPACT = "agent-context-compact"
    const val NOTICE = "agent-context-notice"
    const val HINT = "agent-context-hint"
    const val AUTO_COMPACT_MARK = "agent-context-auto-compact-mark"
}

/**
 * letta-mobile-3io8k: the one compact card under the agent's name in the drawer: the model and its
 * reasoning effort with a chevron, a slim context bar, and "42% of 200k used". Tapping opens the
 * model-and-context sheet (a bottom sheet on a phone, a popover beside the desktop sidebar).
 */
@Composable
fun AgentContextCard(model: AgentContextCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .testTag(AgentContextTags.CARD)
            .semantics { contentDescription = AgentContextStrings.OPEN_SHEET },
    ) {
        Column(
            modifier = Modifier
                .then(if (reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)))
                .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            CardModelLine(model)
            model.meter?.usage?.takeIf { it.maxTokens > 0 }?.let { usage ->
                ContextUsageBar(usage, Modifier.height(AgentContextDimens.cardBarHeight))
            }
            Text(
                text = usageLine(model),
                style = MaterialTheme.typography.labelSmall,
                color = levelColor(model.level),
                maxLines = 1,
                modifier = Modifier.testTag(AgentContextTags.CARD_USAGE),
            )
        }
    }
}

@Composable
private fun CardModelLine(model: AgentContextCardModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = modelLine(model),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(AgentContextTags.CARD_MODEL),
        )
        DisclosureChevron(expanded = false, indicates = ChevronIndication.Sheet, compact = true)
    }
}

/** "claude-opus · High", or the model alone. */
internal fun modelLine(model: AgentContextCardModel): String {
    val name = model.modelLabel?.takeIf { it.isNotBlank() } ?: AgentContextStrings.MODEL_FALLBACK
    val effort = ReasoningTier.of(model.effort)?.let(ModelControlStrings::tierLabel) ?: return name
    return "$name · $effort"
}

internal fun usageLine(model: AgentContextCardModel): String {
    val usage = model.meter?.usage
    return AgentContextStrings.usedLine(
        percent = model.usedPercent,
        window = usage?.maxTokens?.takeIf { it > 0 }?.let(::formatContextTokens),
        used = usage?.usedTokens?.takeIf { it > 0 }?.let(::formatContextTokens),
    )
}

/** Neutral below 70 %, warning to 90 %, error from there (the plan's thresholds). */
@Composable
internal fun levelColor(level: ContextMeterLevel): Color = when (level) {
    ContextMeterLevel.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
    ContextMeterLevel.Warning -> MaterialTheme.colorScheme.tertiary
    ContextMeterLevel.Critical -> MaterialTheme.colorScheme.error
}
