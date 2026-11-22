package com.letta.mobile.ui.components.attached

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.components.foundation.LettaNoticeAction
import com.letta.mobile.ui.components.foundation.color
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.components.motion.LettaPanelMotion
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LettaStatusTone

/** Which edge of the surface it is attached to an [LettaAttachedStatus] rides. */
internal enum class LettaStatusEdge { Top, Bottom }

/**
 * One line of feedback attached to a surface (a prompt, an input): [notice]'s message in a band tinted
 * with its tone's colour on [edge], with [dismiss] (an icon button; give it an icon) where there is one.
 * [LettaNotice.contentTag] goes on the band, for tests; its title is not drawn (a status is one line).
 */
@Immutable
internal data class LettaAttachedStatus(
    val notice: LettaNotice,
    val edge: LettaStatusEdge,
    val dismiss: LettaNoticeAction? = null,
)

/**
 * How strongly a band carries its tone's colour over the surface: a tint, not a container, so an error
 * or warning reads at a glance without out-shouting the surface it is attached to.
 */
private const val BAND_TINT_ALPHA: Float = 0.2f

/**
 * [content] with its [statuses] attached, the way a validated input shows its message: each status is
 * a band in [shape] (the surface's own shape) tinted with its tone, that the surface sits in, its line
 * showing on the band's [LettaAttachedStatus.edge]. The first status is the outermost band. The
 * surface's rounded corners on that edge show the band behind them, so the line reads as part of the
 * surface, not a separate notice. The frame grows and shrinks with the panel motion (LettaPanelMotion) as a status comes
 * and goes (at once under reduced motion). [modifier] goes on the frame.
 *
 * Use it for feedback about one control (a failed send, a nearly-full context window); a note about
 * the page is a LettaInlineNotice, a transient message a snackbar.
 */
@Composable
internal fun LettaAttachedStatusFrame(
    statuses: List<LettaAttachedStatus>,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spec: FiniteAnimationSpec<IntSize> =
        if (LocalLettaReducedMotion.current) snap() else tween(LettaPanelMotion.ENTER_MILLIS, easing = LettaPanelMotion.enterEasing)
    Column(modifier.animateContentSize(spec)) {
        StatusBands(statuses, shape, content)
    }
}

@Composable
private fun StatusBands(statuses: List<LettaAttachedStatus>, shape: Shape, content: @Composable () -> Unit) {
    val status = statuses.firstOrNull()
    if (status == null) {
        content()
        return
    }
    val accent = status.notice.tone.accent()
    val tint = accent.copy(alpha = BAND_TINT_ALPHA).compositeOver(MaterialTheme.colorScheme.surface)
    Column(Modifier.fillMaxWidth().background(tint, shape).testTag(status.notice.contentTag)) {
        if (status.edge == LettaStatusEdge.Top) StatusLine(status, accent)
        StatusBands(statuses.drop(1), shape, content)
        if (status.edge == LettaStatusEdge.Bottom) StatusLine(status, accent)
    }
}

/**
 * The band's line: the tone's glyph in [accent], the message in the surface's text colour (readable on
 * the tint in both themes), and the dismiss where there is one; announced politely.
 */
@Composable
private fun StatusLine(status: LettaAttachedStatus, accent: Color) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LettaControlTokens.iconButtonSm + LettaSpaceTokens.sm * 2)
                .padding(start = LettaSpaceTokens.md, end = LettaSpaceTokens.xs)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LettaIcon(status.notice.tone.glyph(), contentDescription = null, style = LettaIconStyle(tint = accent))
            Text(
                text = status.notice.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = LettaSpaceTokens.sm),
            )
            status.dismiss?.let { dismiss ->
                LettaIconButton(
                    LettaButtonIcon(dismiss.icon ?: LettaIcons.Close, dismiss.label, size = LettaControlTokens.iconButtonSm),
                    onClick = dismiss.onClick,
                    modifier = Modifier.testTag(dismiss.tag),
                    enabled = dismiss.enabled,
                )
            }
        }
    }
}

private fun LettaPanelTone.glyph() = when (this) {
    LettaPanelTone.Error -> LettaIcons.Error
    LettaPanelTone.Warning -> LettaIcons.Warning
    LettaPanelTone.Neutral, LettaPanelTone.Info -> LettaIcons.Info
}

/**
 * A band's colour: the status colour of its tone ([LettaStatusTone.color], so a warning is the theme's
 * amber, not the warning panel's container); `tertiary` where the theme has no warning colour.
 */
@Composable
private fun LettaPanelTone.accent(): Color {
    val status = when (this) {
        LettaPanelTone.Error -> LettaStatusTone.Error
        LettaPanelTone.Warning -> LettaStatusTone.Warning
        LettaPanelTone.Info -> LettaStatusTone.Running
        LettaPanelTone.Neutral -> LettaStatusTone.Idle
    }
    return status.color().takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary
}
