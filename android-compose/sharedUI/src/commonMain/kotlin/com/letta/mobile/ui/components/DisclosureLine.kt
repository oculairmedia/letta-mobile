package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineRail
import com.letta.mobile.ui.components.line.LineSpec
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowMotion
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * The rail a line takes when its caller does not name one. A run provides [LineRail.Step] around its
 * work steps and an open step's details [LineRail.Nested], so a notice drawn there takes that column
 * without knowing where it is.
 */
internal val LocalLineRail = staticCompositionLocalOf { LineRail.Header }

/**
 * The frame every disclosure and notice line in the timeline draws in: one row on [rail]'s column
 * (its start and end inset and its height floor), a fixed glyph slot when [glyph] is set, the
 * caller's [content], and, when [disclosure] is set, the one trailing [DisclosureChevron] with the
 * whole line as its [disclosureToggle]. Lines on one rail therefore share their glyph start, glyph
 * size, label start, chevron edge, chevron size and height floor. [minHeight] raises or lowers the
 * floor for a line whose height its parent depends on (a run's header). [stateDescription] is the
 * line's spoken state when the disclosure's open / closed is not what it should say.
 */
@Composable
internal fun LineFrame(
    rail: LineRail,
    tag: String,
    disclosure: LineDisclosure?,
    modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null,
    stateDescription: String? = null,
    minHeight: Dp = rail.minHeight(),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .testTag(tag)
            .then(if (disclosure != null) Modifier.disclosureToggle(disclosure, stateDescription) else inertLine(rail, stateDescription))
            .padding(rail.insets()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatRowSpacing.lineGap),
    ) {
        if (glyph != null) Box(Modifier.size(ChatRowSpacing.runGlyphSize), contentAlignment = Alignment.Center) { glyph() }
        content()
        if (disclosure != null) {
            val action = stringResource(if (disclosure.expanded) disclosure.labels.collapse else disclosure.labels.expand)
            DisclosureChevron(expanded = disclosure.expanded, contentDescription = action)
        }
    }
}

/** A line with nothing to open: a step says so (it is a step that cannot open); any line keeps its spoken state. */
private fun inertLine(rail: LineRail, stateDescription: String?): Modifier =
    Modifier.semantics(mergeDescendants = true) {
        if (rail != LineRail.Header) disabled()
        stateDescription?.let { this.stateDescription = it }
    }

/**
 * A summary or notice line: the shared [StatusGlyph], the title in the rail's style and the tone's
 * colour (cross-fading as its words change), an optional [detail] after it (a watch's newest event)
 * and [meta] at its end (a count, a state), then [trailing] (a badge) and the chevron. The run header,
 * a background command's report, a Monitor event or watch, and a task notification in every state all
 * draw through here, so they sit in one column on their rail.
 */
@Composable
internal fun SummaryLine(
    spec: LineSpec,
    disclosure: LineDisclosure?,
    modifier: Modifier = Modifier,
    rail: LineRail = LocalLineRail.current,
    detail: String? = null,
    meta: String? = null,
    stateDescription: String? = null,
    minHeight: Dp = rail.minHeight(),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val style = rail.titleStyle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LineFrame(
        rail = rail,
        tag = spec.tags.row,
        disclosure = disclosure,
        modifier = modifier,
        glyph = { StatusGlyph(spec.tone, Modifier.testTag(spec.tags.glyph), spec.glyphLabel) },
        stateDescription = stateDescription,
        minHeight = minHeight,
    ) {
        val titleWeight = if (detail != null) Modifier.weight(1f, fill = false) else Modifier.weight(1f)
        LineTitle(spec.title, style, spec.tone.titleColor(), titleWeight)
        detail?.let { LineText(it, style, muted, Modifier.weight(1f)) }
        meta?.let { LineText(it, style, muted, Modifier) }
        trailing?.invoke(this)
    }
}

/**
 * A step with a preview under its label (a reasoning: "Thought for 2.4s" over its first line): the
 * shared [StatusGlyph], the label in the step style and the preview under it, on [LineRail.Step].
 */
@Composable
internal fun StepLine(spec: LineSpec, disclosure: LineDisclosure?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LineFrame(
        rail = LineRail.Step,
        tag = spec.tags.row,
        disclosure = disclosure,
        modifier = modifier,
        glyph = { StatusGlyph(spec.tone, Modifier.testTag(spec.tags.glyph), spec.glyphLabel) },
    ) {
        Column(Modifier.weight(1f)) {
            LineText(spec.title, LineRail.Step.titleStyle(), MaterialTheme.colorScheme.onSurface, Modifier)
            spec.subtitle?.let { LineText(it, MaterialTheme.typography.labelSmall, muted, Modifier) }
        }
    }
}

/**
 * A divider across the column with a quiet label in it (where the conversation was compacted,
 * entering or leaving plan mode): a hairline either side of [label] on [rail]'s line, the shared
 * chevron at the end when it opens something.
 */
@Composable
internal fun DividerLine(label: String, tag: String, rail: LineRail, disclosure: LineDisclosure?, modifier: Modifier = Modifier) {
    LineFrame(rail = rail, tag = tag, disclosure = disclosure, modifier = modifier) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        LineText(label, rail.titleStyle(), MaterialTheme.colorScheme.onSurfaceVariant, Modifier)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** One line of a line's words, ellipsized. */
@Composable
internal fun LineText(text: String, style: TextStyle, color: Color, modifier: Modifier) {
    Text(text = text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The title, cross-fading when its words change ("Working…" to "Worked for 2s"), keyed on the words. */
@Composable
private fun LineTitle(title: String, style: TextStyle, color: Color, modifier: Modifier) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedContent(
        targetState = title,
        transitionSpec = { ChatRowMotion(reducedMotion).terminalSwap() },
        contentAlignment = Alignment.CenterStart,
        label = "lineTitle",
        modifier = modifier,
    ) { shown -> LineText(shown, style, color, Modifier) }
}

/** The rail's label style: the run header's on the header rail, the step line's on a step or inside one. */
@Composable
@ReadOnlyComposable
internal fun LineRail.titleStyle(): TextStyle = when (this) {
    LineRail.Header -> ChatRowType.runHeaderTitle
    LineRail.Step, LineRail.Nested -> ChatRowType.stepTitle
}

/** The rail's height floor: a run header's folding line, or a step line. */
internal fun LineRail.minHeight(): Dp = when (this) {
    LineRail.Header -> ChatRowSpacing.runHeaderToggleMinHeight
    LineRail.Step, LineRail.Nested -> ChatRowSpacing.toolStepMinHeight
}

/** The rail's insets: a header's glyph sits at the header inset, a step's at the step inset, a nested line at its parent's. */
private fun LineRail.insets(): PaddingValues = when (this) {
    LineRail.Header -> PaddingValues(
        start = ChatRowSpacing.runHeaderHorizontal,
        end = ChatRowSpacing.lineEnd,
        top = ChatRowSpacing.runHeaderVertical,
        bottom = ChatRowSpacing.runHeaderVertical,
    )
    LineRail.Step -> PaddingValues(
        start = ChatRowSpacing.stepHorizontal,
        end = ChatRowSpacing.lineEnd,
        top = ChatRowSpacing.stepVertical,
        bottom = ChatRowSpacing.stepVertical,
    )
    LineRail.Nested -> PaddingValues(top = ChatRowSpacing.stepVertical, bottom = ChatRowSpacing.stepVertical)
}

/** The title's colour says the tone: the secondary ink waiting on the person, the error ink failed, muted otherwise. */
@Composable
@ReadOnlyComposable
private fun StatusTone.titleColor(): Color = when (this) {
    StatusTone.Attention -> MaterialTheme.colorScheme.secondary
    StatusTone.Failed -> MaterialTheme.colorScheme.error
    StatusTone.Working -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatRowAlpha.workingTitle)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
