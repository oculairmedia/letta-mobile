package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.chat.AgentOrb

/** Coarse status of the active conversation for the bottom bar. */
internal enum class NowActiveStatus { Idle, Thinking, Streaming, Error }

internal fun nowActiveStatus(
    isThinking: Boolean,
    isStreaming: Boolean,
    hasError: Boolean,
): NowActiveStatus = when {
    hasError -> NowActiveStatus.Error
    isThinking -> NowActiveStatus.Thinking
    isStreaming -> NowActiveStatus.Streaming
    else -> NowActiveStatus.Idle
}

internal data class NowActiveBarState(
    val conversationTitle: String,
    val agentName: String,
    val orbIndex: Int,
    val status: NowActiveStatus,
    /** Agent working in a conversation OTHER than the active one, if any. */
    val backgroundWorkAgentName: String?,
)

internal data class NowActiveBarActions(
    /** Bring the active conversation on screen (bar body click). */
    val onOpenConversation: () -> Unit,
    /** Jump to the conversation doing background work (chip click). */
    val onJumpToBackgroundWork: () -> Unit,
)

private val NowActiveStatus.label: String
    get() = when (this) {
        NowActiveStatus.Idle -> ""
        NowActiveStatus.Thinking -> "thinking…"
        NowActiveStatus.Streaming -> "responding…"
        NowActiveStatus.Error -> "needs attention"
    }

/**
 * Spotify-style persistent bottom bar: always shows the ACTIVE conversation
 * (updated whenever the user clicks a conversation, not just an agent) with
 * its live status, visible across every destination so leaving the chat
 * never loses track of what is running. A secondary chip surfaces background
 * work happening in a different conversation.
 */
@Composable
internal fun DesktopNowActiveBar(
    state: NowActiveBarState,
    actions: NowActiveBarActions,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onOpenConversation)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentOrb(index = state.orbIndex, size = 36.dp, cornerRadius = 9.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.conversationTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = state.agentName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.status != NowActiveStatus.Idle) {
                            StatusDot(state.status)
                            Text(
                                text = state.status.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.status == NowActiveStatus.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
                state.backgroundWorkAgentName?.let { name ->
                    BackgroundWorkChip(agentName = name, onClick = actions.onJumpToBackgroundWork)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: NowActiveStatus) {
    val transition = rememberInfiniteTransition(label = "nowActiveDot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "nowActiveDotAlpha",
    )
    val color = if (status == NowActiveStatus.Error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun BackgroundWorkChip(agentName: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(NowActiveStatus.Thinking)
            Text(
                text = "$agentName is working",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
