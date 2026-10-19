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
 * [title] is null for a one-line note; [onRetry] null for a message with nothing to retry.
 */
@Composable
fun LettaInlineError(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = DEFAULT_TITLE,
    onRetry: (() -> Unit)? = null,
    retrying: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(LettaInlineErrorTags.ERROR),
    ) {
        Row(
            modifier = Modifier.padding(if (title == null) LettaDimens.Space.md else LettaDimens.Space.lg),
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
            if (onRetry != null) {
                OutlinedButton(onClick = onRetry, enabled = !retrying, modifier = Modifier.testTag(LettaInlineErrorTags.RETRY)) {
                    Text(if (retrying) "Retrying…" else "Retry")
                }
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
}
