package com.letta.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * letta-mobile-bglj6.1: the chat timeline's type roles, lifted from designsystem's LettaChatTheme
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

    /** How far a settled run's body tucks up under its "Thought for 4s" header. */
    val completedRunBodyLift: Dp = 22.dp

    /** The thinking row's reserved height and its text's floor. */
    val thinkingRowHeight: Dp = LettaDimens.Space.xxl
    val thinkingTextMinHeight: Dp = LettaDimens.Space.xl
}

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
