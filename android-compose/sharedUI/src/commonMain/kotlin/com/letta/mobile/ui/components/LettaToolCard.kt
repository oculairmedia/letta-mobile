package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_card_detail
import com.letta.mobile.sharedui.resources.rows_tool_card_hide_detail
import com.letta.mobile.sharedui.resources.rows_tool_card_show_detail
import com.letta.mobile.ui.components.disclosure.DisclosureButton
import com.letta.mobile.ui.components.disclosure.DisclosureRow
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.StatusGlyph
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.components.foundation.lettaInlineTarget
import com.letta.mobile.ui.components.foundation.lettaRowTarget
import com.letta.mobile.ui.components.line.DefaultDisclosureLabels
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineDisclosureLabels
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import com.letta.mobile.ui.theme.ChatRowMotion
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** How a [LettaToolCard] sits in the timeline. */
internal enum class LettaToolCardStyle {
    /** A compact raised card about three fifths of the column wide, its header inside it (a sent message, a created subagent). */
    Card,

    /** A quiet line (icon, label, title, chevron) that opens a raised panel the column's width under it (a loaded skill). */
    Line,
}

/**
 * A tool card's header: its [icon] (a small spinner instead while [working]), its muted [label]
 * ("Sent message to", "Loaded skill") and its [title] in the stronger weight (an agent, a skill, a
 * task), on the label's line or, when [stacked], on a line of its own (two lines at most).
 */
@Immutable
internal class LettaToolCardHeader(
    val label: String,
    val title: String? = null,
    val icon: ImageVector? = null,
    val stacked: Boolean = false,
    val working: Boolean = false,
)

/** A card's one-line [text] preview, which its own chevron (labelled by [labels]) opens in full. */
@Immutable
internal class LettaToolCardPreview(val text: String, val labels: LineDisclosureLabels, val tag: String = "")

/**
 * What a [LettaToolCard] carries besides its header and body: its [style]; [trailing] content at the
 * header's end (a status, a badge); a [preview]; the [expansion] that opens its body (a "Detail" link
 * on a card, the whole line with its chevron on a line); and the [action] the whole card performs (a
 * card only: opening a subagent).
 */
@Immutable
internal class LettaToolCardParts(
    val style: LettaToolCardStyle = LettaToolCardStyle.Card,
    val trailing: (@Composable RowScope.() -> Unit)? = null,
    val preview: LettaToolCardPreview? = null,
    val expansion: LineDisclosure? = null,
    val action: LettaMenuItem? = null,
)

/**
 * The one tool card of the timeline: a tool call that is an event of its own (a message sent to
 * another agent, a subagent created, a skill loaded) rather than a step. Every such card is this
 * component: a [header], [LettaToolCardParts] and a [body] built from the shared blocks
 * ([LettaToolCardFacts], [LettaToolCardDivider], [LettaToolCardSection]), so no card lays itself out.
 *
 * Its surface is the medium shape token on surfaceContainerHigh over an outlineVariant hairline, so
 * Dark and Light follow the theme. Hover and press are rounded (the card's own shape for its action,
 * [lettaRowTarget] for its links and line). Every control is a button to assistive technology that
 * the keyboard reaches; the body's toggle says whether it is open.
 */
@Composable
internal fun LettaToolCard(
    header: LettaToolCardHeader,
    modifier: Modifier = Modifier,
    parts: LettaToolCardParts = LettaToolCardParts(),
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    when (parts.style) {
        LettaToolCardStyle.Card -> CardStyle(header, modifier, parts, body)
        LettaToolCardStyle.Line -> LineStyle(header, modifier, parts, body)
    }
}

@Composable
private fun CardStyle(
    header: LettaToolCardHeader,
    modifier: Modifier,
    parts: LettaToolCardParts,
    body: (@Composable ColumnScope.() -> Unit)?,
) {
    val shape = MaterialTheme.shapes.medium
    val clicks = parts.action?.let { item ->
        Modifier
            .lettaClickable(role = Role.Button, onClickLabel = item.label, target = lettaInlineTarget(shape), onClick = item.onClick)
            .then(if (item.tag.isEmpty()) Modifier else Modifier.testTag(item.tag))
    } ?: Modifier
    ToolCardSurface(modifier.compactCardWidth().then(clicks)) {
        CardHeader(header, parts)
        parts.preview?.let { ToolCardPreviewRow(it) }
        ExpandingBody(parts.expansion, body)
    }
}

@Composable
private fun LineStyle(
    header: LettaToolCardHeader,
    modifier: Modifier,
    parts: LettaToolCardParts,
    body: (@Composable ColumnScope.() -> Unit)?,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        DisclosureRow(disclosure = parts.expansion.takeIf { body != null }, modifier = Modifier.testTag(LETTA_TOOL_CARD_LINE_TAG)) {
            HeaderLead(header)
            Text(
                text = header.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            header.title?.let { LettaToolCardTitle(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
            parts.trailing?.invoke(this)
        }
        if (body != null) {
            ToolCardAnimated(parts.expansion?.expanded == true) {
                ToolCardSurface(Modifier.fillMaxWidth()) { body() }
            }
        }
    }
}

/** The raised rounded surface every tool card draws on. */
@Composable
private fun ToolCardSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            content = content,
        )
    }
}

@Composable
private fun CardHeader(header: LettaToolCardHeader, parts: LettaToolCardParts) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderLead(header)
            Text(
                text = header.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            header.title?.takeUnless { header.stacked }?.let { LettaToolCardTitle(it, Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            parts.trailing?.invoke(this)
            parts.expansion?.let { DetailLink(it) }
        }
        header.title?.takeIf { header.stacked }?.let { LettaToolCardTitle(it, maxLines = 2) }
    }
}

/** The header's icon, or the working spinner in its place. */
@Composable
private fun HeaderLead(header: LettaToolCardHeader) {
    if (header.working) {
        StatusGlyph(StatusTone.Working)
    } else {
        header.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Control.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LettaToolCardTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** "Detail": a quiet text button that opens or closes the card's body, saying which it will do and whether it is open. */
@Composable
private fun DetailLink(expansion: LineDisclosure) {
    val action = stringResource(if (expansion.expanded) Res.string.rows_tool_card_hide_detail else Res.string.rows_tool_card_show_detail)
    val state = stringResource(if (expansion.expanded) DefaultDisclosureLabels.expandedState else DefaultDisclosureLabels.collapsedState)
    Text(
        text = stringResource(Res.string.rows_tool_card_detail),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .lettaClickable(role = Role.Button, onClickLabel = action, target = lettaRowTarget(), onClick = expansion.onToggle)
            .semantics { stateDescription = state }
            .testTag(LETTA_TOOL_CARD_DETAIL_TAG)
            .padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.hair),
    )
}

/** The preview: one line of [LettaToolCardPreview.text], and the round chevron that opens all of it. */
@Composable
private fun ToolCardPreviewRow(preview: LettaToolCardPreview) {
    var open by rememberSaveable(preview.text) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = preview.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(if (preview.tag.isEmpty()) Modifier else Modifier.testTag(preview.tag)),
        )
        DisclosureButton(LineDisclosure(open, preview.labels) { open = !open })
    }
}

@Composable
private fun ExpandingBody(expansion: LineDisclosure?, body: (@Composable ColumnScope.() -> Unit)?) {
    if (body == null || expansion == null) return
    ToolCardAnimated(expansion.expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            LettaToolCardDivider()
            body()
        }
    }
}

@Composable
private fun ToolCardAnimated(visible: Boolean, content: @Composable () -> Unit) {
    val motion = ChatRowMotion(LocalReducedMotion.current)
    AnimatedVisibility(visible = visible, enter = motion.expansionEnter(), exit = motion.expansionExit(), label = "toolCardBody") {
        content()
    }
}

/** One row of a [LettaToolCardFacts] list: a [label] and its [value], in monospace (a path, an id) when [monospace]. */
@Immutable
internal class LettaToolCardFact(val label: String, val value: String, val monospace: Boolean = false)

/** A body's key/value list: muted labels in a fixed column, values wrapping beside them. */
@Composable
internal fun LettaToolCardFacts(facts: List<LettaToolCardFact>) {
    if (facts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        facts.forEach { fact ->
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
                Text(
                    text = fact.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(LettaDimens.Pane.toolCardFactLabelWidth),
                )
                Text(
                    text = fact.value,
                    style = if (fact.monospace) LettaTextStyles.mono else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A hairline between a body's blocks. */
@Composable
internal fun LettaToolCardDivider() {
    HorizontalDivider(
        thickness = LettaDimens.Stroke.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
    )
}

/**
 * A titled section of a body ("Contents", "Prompt") whose [content] (rendered markdown, plain text)
 * scrolls inside a capped height ([LettaDimens.Pane.toolCardSectionMaxHeight]).
 */
@Composable
internal fun LettaToolCardSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LettaDimens.Pane.toolCardSectionMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) { content() }
    }
}

/**
 * Three fifths of the column; the whole column where three fifths would fall under the card minimum
 * (a phone), so a narrow column never gets a cramped card beside an empty strip.
 */
private fun Modifier.compactCardWidth(): Modifier = layout { measurable, constraints ->
    val minimum = LettaDimens.Pane.compactCardMinWidth.roundToPx()
    val column = if (constraints.hasBoundedWidth) constraints.maxWidth else minimum
    val share = (column * COMPACT_CARD_FRACTION).roundToInt()
    val width = if (share < minimum) column else share
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private const val COMPACT_CARD_FRACTION = 0.6f

/** A tool card's "Detail" link. */
internal const val LETTA_TOOL_CARD_DETAIL_TAG = "tool-card-detail"

/** A line-style tool card's header line (its toggle). */
internal const val LETTA_TOOL_CARD_LINE_TAG = "tool-card-line"
