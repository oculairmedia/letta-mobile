package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import com.letta.mobile.data.context.formatContextPercent
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_context_nearly_full
import com.letta.mobile.sharedui.resources.composer_dismiss_error
import com.letta.mobile.ui.components.attached.LettaAttachedStatus
import com.letta.mobile.ui.components.attached.LettaStatusEdge
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.components.foundation.LettaNoticeAction
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import org.jetbrains.compose.resources.stringResource

/** At this share of the agent's context window the composer warns that the window is nearly full. */
internal const val COMPOSER_CONTEXT_WARNING_FRACTION: Float = 0.9f

/**
 * What the prompt says about itself right now, attached to it (LettaAttachedStatusFrame), outermost
 * first: the composer's error (a refused attachment, a blocked send) on its top edge with a dismiss,
 * then, where [withContextWarning], a warning on its bottom edge once the context window is
 * [COMPOSER_CONTEXT_WARNING_FRACTION] full. The warning has no dismiss: it goes when the window has room.
 */
@Composable
internal fun composerStatuses(model: ComposerModel, withContextWarning: Boolean): List<LettaAttachedStatus> {
    val error = model.composer.error?.let { message ->
        LettaAttachedStatus(
            notice = LettaNotice(message, tone = LettaPanelTone.Error, contentTag = ComposerTestTags.ERROR),
            edge = LettaStatusEdge.Top,
            dismiss = LettaNoticeAction(
                label = stringResource(Res.string.composer_dismiss_error),
                onClick = model.actions::clearComposerError,
                icon = LettaIcons.Close,
            ),
        )
    }
    val usage = model.composer.contextUsage?.usage?.takeIf { withContextWarning && it.maxTokens > 0 }
    val warning = usage?.takeIf { it.usedFraction >= COMPOSER_CONTEXT_WARNING_FRACTION }?.let {
        LettaAttachedStatus(
            notice = LettaNotice(
                message = stringResource(Res.string.composer_context_nearly_full, formatContextPercent(it.usedFraction)),
                tone = LettaPanelTone.Warning,
                contentTag = ComposerTestTags.CONTEXT_WARNING,
            ),
            edge = LettaStatusEdge.Bottom,
        )
    }
    return listOfNotNull(error, warning)
}
