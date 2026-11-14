package com.letta.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The chat timeline's type roles, lifted from designsystem's LettaChatTheme
 * (ChatTypography) and TypeHierarchy so the shared rows draw the legacy Android timeline's text
 * without depending on the Android-only designsystem module. Every style derives from the host's
 * MaterialTheme typography, so the Letta type scale (Inter on Android) still applies.
 */
object ChatRowType {
    /** "Thought" / "Thinking…": TypeHierarchy.sectionTitle (titleSmallEmphasized, which the Letta scale leaves equal to titleSmall). */
    val sectionTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    /** The reasoning preview beside "Thought": TypeHierarchy.listItemSupporting. */
    val listItemSupporting: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)

    /**
     * A run's one summary line ("Worked for 1m 7s · Ran 4 commands"): the muted label every run
     * summary, tool summary and its counts share, no heavier than the prose it sits over.
     */
    val summaryLine: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** A run's summary line ("Worked · 1.2s · 2 tools"): legacy RunActivityDisclosure's labelMedium. */
    val runHeaderTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium

    /** A work step's line ("Ran  ./gradlew test"), the label every step-rail line shares. */
    internal val stepTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** Command lines, output and arguments: the body size in monospace. */
    internal val monoBody: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    /** A path or a stream label: the small label size in monospace. */
    internal val monoLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

    /** A card's title (an approval, a question, generated UI). */
    internal val cardTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    /** A named item inside a card (a tool's name, a diff's path, an artifact's status). */
    internal val itemTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)

    /** The headline of a whole-timeline panel (the failure panel, the welcome). */
    internal val panelHeadline: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)

    /** The "You" label on a prompt bubble: ChatTypography.roleLabel. */
    val roleLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = LettaChatTokens.typography.roleLabelLetterSpacingSp.sp,
        )
}

/**
 * The chat timeline's rhythm, from LettaChatTokens (ChatDimens / ChatShapes on Android): a tight
 * beat between the steps of one turn, a looser section break between speakers.
 */
object ChatRowSpacing {
    /** Between steps of one run, and before a reasoning or tool row. */
    val grouped: Dp = LettaChatTokens.dimens.groupedMessageSpacingDp.dp

    /** Before a new speaker's row or a new run. */
    val ungrouped: Dp = LettaChatTokens.dimens.ungroupedMessageSpacingDp.dp

    /** Between the parts of one message (role label, text, attachments). */
    val messagePart: Dp = LettaChatTokens.dimens.messageSpacingDp.dp

    val bubblePaddingHorizontal: Dp = LettaChatTokens.dimens.bubblePaddingHorizontalDp.dp
    val bubblePaddingVertical: Dp = LettaChatTokens.dimens.bubblePaddingVerticalDp.dp

    /** The timeline's side gutter. */
    val contentPaddingHorizontal: Dp = LettaChatTokens.dimens.contentPaddingHorizontalDp.dp

    /** Above the newest and below the oldest row. */
    val listEdge: Dp = LettaSpacingTokens.CARD_GAP.dp

    /** Above the newest reply's delivery time, on top of [messagePart]. */
    val deliveryTimeGap: Dp = LettaSpacingTokens.XXXS.dp

    /** A prompt bubble never spans the whole column: it reads as the reader's own card. */
    const val bubbleMaxWidthFraction: Float = 0.88f

    /**
     * A run's summary line ("Worked for 1m 7s", "Ran 4 commands"): the same floor and the same
     * vertical inset whether or not it carries a chevron, so the two read as one rail.
     */
    val summaryLineMinHeight: Dp = LettaDimens.Control.icon
    val summaryLineVertical: Dp = LettaDimens.Space.xs

    /**
     * A run's header (legacy RunActivityDisclosure): its floor, taller when it folds the run, and its
     * inset.
     */
    val runHeaderMinHeight: Dp = LettaDimens.Space.xxl
    val runHeaderToggleMinHeight: Dp = LettaDimens.Orb.railSlotHeight
    val runHeaderHorizontal: Dp = LettaDimens.Space.xs
    val runHeaderVertical: Dp = LettaDimens.Space.hair

    /**
     * What a summary line's floor leaves under its one-line label (the floor less the label's line,
     * halved): the plain line's [runHeaderMinHeight], and the folding line's [runHeaderToggleMinHeight].
     * The run's body lifts this far plus its first step's beat, so the response under a showing line
     * starts right under the label (the same distance as a paragraph break), not a section break below it.
     */
    val runHeaderFloor: Dp = LettaDimens.Space.sm
    val runHeaderToggleFloor: Dp = LettaDimens.Space.md + LettaDimens.Space.hair

    /** The run summary line's status glyph (check, spinner, warning). */
    val runGlyphSize: Dp = LettaDimens.Control.iconSm

    /** The working glyph's ring and spinner stroke. */
    internal val runGlyphStroke: Dp = 1.5.dp

    /** A step-rail line's start inset (a tool call, a reasoning): its glyph sits this far in. */
    internal val stepHorizontal: Dp = LettaDimens.Space.sm

    /** A step-rail line's top and bottom inset, so a step with a preview under its label keeps air around it. */
    internal val stepVertical: Dp = LettaDimens.Space.xs

    /** Every line's end inset, so the header's chevron and every step's chevron share one edge. */
    internal val lineEnd: Dp = LettaDimens.Space.sm

    /** Between a line's glyph, its words and its chevron. */
    internal val lineGap: Dp = LettaDimens.Space.sm

    /**
     * An expanded run's work steps (reasoning, the tool line) sit indented
     * under its summary line, flush with the summary's label (past its inset and glyph).
     */
    val runStepIndent: Dp = runHeaderHorizontal + runGlyphSize + LettaDimens.Space.sm

    /**
     * An open tool step's details sit flush with its title: past the step row's (LettaListRow's)
     * start padding, its status glyph and the gap after it.
     */
    val runStepDetailIndent: Dp = LettaDimens.Space.sm + runGlyphSize + LettaDimens.Space.sm

    /** A tool step line's height, the same open or closed, running or settled. */
    val toolStepMinHeight: Dp = 36.dp

    /** The thinking row's reserved height and its text's floor. */
    val thinkingRowHeight: Dp = LettaDimens.Space.xxl
    val thinkingTextMinHeight: Dp = LettaDimens.Space.xl
}

/** A card in the timeline (an approval, generated UI, a loading bubble): the rounded container's corners. */
internal val ChatRowCardShape: Shape = RoundedCornerShape(LettaDimens.Radius.md)

/** An inset inside a row (a code block, a tool's output, a small control): the tighter corners. */
internal val ChatRowInsetShape: Shape = RoundedCornerShape(LettaDimens.Radius.sm)

/** Prompt bubble geometry (designsystem MessageBubbleShape): rounded, with a tight corner at the speaker's top. */
object ChatBubbleShapes {
    private val radius: Dp = LettaChatTokens.shapes.bubbleRadiusDp.dp
    private val tight: Dp = radius * TIGHT_CORNER_RATIO

    /** The user's bubble; [continues] is true when the next row is the same speaker's, which tightens the bottom-end. */
    fun user(continues: Boolean = false): RoundedCornerShape = RoundedCornerShape(
        topStart = radius,
        topEnd = tight,
        bottomEnd = if (continues) tight else radius,
        bottomStart = radius,
    )

    /** An agent bubble (error frames): the tight corner is at the top-start. */
    fun agent(continues: Boolean = false): RoundedCornerShape = RoundedCornerShape(
        topStart = tight,
        topEnd = radius,
        bottomEnd = radius,
        bottomStart = if (continues) tight else radius,
    )

    private const val TIGHT_CORNER_RATIO = 0.25f
}
