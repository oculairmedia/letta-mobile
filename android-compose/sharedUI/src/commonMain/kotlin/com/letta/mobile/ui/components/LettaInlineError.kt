package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.components.foundation.LettaNoticeAction
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.components.foundation.LettaInlineNotice as SharedInlineNotice

/**
 * The one inline error a page shows where its content failed to load (Memory, Schedules, Skills,...). It stays in the page's layout — unlike the
 * transient overlays of [com.letta.mobile.ui.host.OverlayHost] — so a failed load reads the same
 * everywhere and offers its retry in place. Replaces desktop's DesktopInlineError and the pages'
 * own error cards.
 *
 * [title] is null for a one-line note; [onRetry] null for a message with nothing to retry;
 * [onDismiss] non-null adds a "Dismiss" after the retry, for an operation failure the user clears.
 *
 * [modifier] goes on the outer surface, so a page's own test tag passed in it stays findable; the
 * shared [LettaInlineErrorTags.ERROR] sits on the content row inside it.
 */
@Composable
fun LettaInlineError(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = DEFAULT_TITLE,
    onRetry: (() -> Unit)? = null,
    retrying: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    LettaInlineNotice(
        message = message,
        modifier = modifier,
        title = title,
        actionLabel = if (retrying) "Retrying…" else "Retry",
        onAction = onRetry,
        actionEnabled = !retrying,
        dismissLabel = "Dismiss",
        onDismiss = onDismiss,
    )
}

/**
 * [LettaInlineError]'s surface with the caller's own action and dismiss labels: the shared
 * [com.letta.mobile.ui.components.foundation.LettaInlineNotice] at [LettaPanelTone.Error], keeping
 * this package's test tags.
 */
@Composable
internal fun LettaInlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String = "",
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    actionTag: String = LettaInlineErrorTags.RETRY,
    dismissLabel: String = "",
    onDismiss: (() -> Unit)? = null,
    dismissTag: String = LettaInlineErrorTags.DISMISS,
) {
    SharedInlineNotice(
        notice = LettaNotice(message, LettaPanelTone.Error, title, LettaInlineErrorTags.ERROR),
        modifier = modifier,
        action = onAction?.let { LettaNoticeAction(actionLabel, it, actionTag, actionEnabled) },
        dismiss = onDismiss?.let { LettaNoticeAction(dismissLabel, it, dismissTag) },
    )
}

private const val DEFAULT_TITLE = "Couldn't load this view"

/** Test tags of [LettaInlineError]. */
object LettaInlineErrorTags {
    const val ERROR = "letta-inline-error"
    const val RETRY = "letta-inline-error-retry"
    const val DISMISS = "letta-inline-error-dismiss"
}
