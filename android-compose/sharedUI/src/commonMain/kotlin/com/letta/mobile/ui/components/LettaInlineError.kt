package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 *  (hard cut S13, P10): the one inline error a page shows where its content
 * failed to load (Memory, Schedules, Skills,...). It stays in the page's layout — unlike the
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
 * [LettaInlineError] with the caller's own action and dismiss labels ("Set up", "Retry" in the
 * caller's language) and, where it needs them, its own button tags ([actionTag], [dismissTag]): the
 * same surface and layout, for a notice whose action is not a retry. A button shows when its
 * handler is given.
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .testTag(LettaInlineErrorTags.ERROR)
                .padding(if (title == null) LettaDimens.Space.md else LettaDimens.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) Icon(LettaIcons.Error, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = if (title == null) 1f else MESSAGE_ALPHA),
                )
            }
            if (onAction != null) {
                OutlinedButton(onClick = onAction, enabled = actionEnabled, modifier = Modifier.testTag(actionTag)) {
                    Text(actionLabel)
                }
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) { Text(dismissLabel) }
            }
        }
    }
}

private const val DEFAULT_TITLE = "Couldn't load this view"
private const val MESSAGE_ALPHA = 0.84f

/** Test tags of [LettaInlineError]. */
object LettaInlineErrorTags {
    const val ERROR = "letta-inline-error"
    const val RETRY = "letta-inline-error-retry"
    const val DISMISS = "letta-inline-error-dismiss"
}
