package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 *
 * For the c87t.2 actionable variant (gateway *refused* the substitution),
 * see [ClientModeConversationNotResumableBanner].
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

/**
 * letta-mobile-c87t.2: actionable banner shown when the lettabot gateway
 * *refuses* to silently swap conversations. Unlike [ClientModeConversationSwapBanner],
 * the user is still anchored to the original conversation here — nothing has
 * been migrated. The banner offers two paths:
 *
 *   * **Start a fresh chat** — discards the unresumable conversation pointer
 *     and re-sends the user's last message under a brand-new conversation.
 *   * **Dismiss** — banner clears; user stays in the prior conversation
 *     (likely sees the same banner again on the next send unless server
 *     conditions change). Useful when the user wants to investigate why
 *     the conversation can't be resumed before committing to a fresh one.
 *
 * Visual: a warmer amber than the informational variant, plus an explicit
 * call-to-action button. The doubled-response symptom (cm-assist Local +
 * SSE Confirmed under a silently-swapped conv) is impossible from this
 * branch because we never accept the substitution.
 */
@Composable
fun ClientModeConversationNotResumableBanner(
    visible: Boolean,
    onStartFresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    requestedConversationIdSuffix: String? = null,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }) + expandVertically(),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }) + shrinkVertically(),
    ) {
        val bg = Color(0xFFF57F17) // deeper amber — actionable
        val fg = Color(0xFFFFFFFF)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "This conversation can't be resumed",
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                    )
                    val suffix = requestedConversationIdSuffix?.let { " (\u2026$it)" }.orEmpty()
                    Text(
                        text = "The chat harness lost track of conversation$suffix " +
                            "after a long pause. Send anyway and we'll start a fresh chat, " +
                            "or dismiss to stay here and try again later.",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not now", color = fg)
                }
                FilledTonalButton(
                    onClick = onStartFresh,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFFFFFF),
                        contentColor = Color(0xFF1A1A1A),
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Start fresh chat")
                }
            }
        }
    }
}
