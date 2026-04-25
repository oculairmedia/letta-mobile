package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Banner shown when the lettabot WS gateway substituted a fresh conversation
 * for the one mobile asked to resume — i.e. the requested conversation was
 * unrecoverable on the gateway/SDK side and a new conversation was opened.
 *
 * Surfaces the substitution non-modally so the user can opt-in to navigating
 * to the new conversation if desired. Dismissable. See `letta-mobile-c87t`.
 */
@Composable
fun ClientModeConversationSwapBanner(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    requestedConversationIdSuffix: String? = null,
    newConversationIdSuffix: String? = null,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }) + expandVertically(),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }) + shrinkVertically(),
    ) {
        // Amber/warning surface so the user notices, but not error-red — the
        // gateway recovered cleanly, this is just disclosure.
        val bg = Color(0xFFF9A825)
        val fg = Color(0xFF1A1A1A)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continued in a new conversation",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
                val subtitleSuffix = listOfNotNull(
                    requestedConversationIdSuffix?.let { "from \u2026$it" },
                    newConversationIdSuffix?.let { "to \u2026$it" },
                ).joinToString(" ")
                val subtitle = if (subtitleSuffix.isNotEmpty()) {
                    "Your previous conversation couldn't be resumed; the harness opened a fresh one ($subtitleSuffix)."
                } else {
                    "Your previous conversation couldn't be resumed; the harness opened a fresh one."
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = fg,
                )
            }
        }
    }
}
