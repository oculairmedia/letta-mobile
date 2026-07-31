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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import com.letta.mobile.data.chat.runtime.nowActiveStatus
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState

@Immutable
internal data class NowActiveBarState(
    val conversationTitle: String,
    val agentName: String,
    val orbIndex: Int,
    val status: NowActiveStatus,
    /** Agent working in a conversation OTHER than the active one, if any. */
    val backgroundWorkAgentName: String?,
    val avatarCompanionActive: Boolean = false,
)

@Immutable
internal data class NowActiveBarActions(
    /** Bring the active conversation on screen (bar body click). */
    val onOpenConversation: () -> Unit,
    /** Jump to the conversation doing background work (chip click). */
    val onJumpToBackgroundWork: () -> Unit,
    /** Toggle the desktop avatar companion (bar trailing control). */
    val onAvatarCompanion: () -> Unit = {},
    /** Interrupt the pinned conversation's in-flight run (stop button). */
    val onStopRun: () -> Unit = {},
)

private val NowActiveStatus.label: String
    get() = when (this) {
        NowActiveStatus.Idle -> ""
        NowActiveStatus.Thinking -> "thinking…"
        NowActiveStatus.Streaming -> "responding…"
        NowActiveStatus.Stopping -> "stopping…"
        NowActiveStatus.Error -> "needs attention"
    }

@Immutable
internal data class NowActiveBarHostState(
    val thinkingConversationId: String?,
    val isStreamingReplySelected: Boolean,
    val avatarStyleByAgentId: Map<String, Int>,
    val fallbackOrbIndex: Int,
    val avatarCompanionActive: Boolean,
)

@Immutable
internal data class NowActiveBarHostActions(
    /** Select + reveal the given conversation (bar body and work chip). */
    val onOpenConversation: (String) -> Unit,
    val onAvatarCompanion: () -> Unit,
    /** Interrupt the given conversation's in-flight run. */
    val onStopRun: (String) -> Unit,
)

/**
 * Derives the bar's pinned conversation and live status from controller
 * state: pinned to the conversation the user LAST PROMPTED (sticky across
 * browsing, like now-playing), falling back to the selection until the first
 * send. Renders nothing when there is no conversation at all.
 */
@Composable
internal fun DesktopNowActiveBarHost(
    chatController: DesktopChatController,
    chatState: DesktopChatSurfaceState,
    host: NowActiveBarHostState,
    actions: NowActiveBarHostActions,
) {
    val lastPromptedId by chatController.lastPromptedConversationId.collectAsState()
    val streamingId by chatController.streamingConversationId.collectAsState()
    // letta-mobile-lgns8.19: an abort was sent but the terminal frame hasn't
    // landed — the run is still live, so the bar says "stopping…" and the stop
    // button stays available as the local force-clear escape hatch.
    val cancellingId by chatController.cancellingConversationId.collectAsState()
    val barConversation = lastPromptedId
        ?.let { id -> chatState.conversations.firstOrNull { it.id == id } }
        ?: chatState.selectedConversation
        ?: return
    val barIsSelected = barConversation.id == chatState.selectedConversationId
    DesktopNowActiveBar(
        state = NowActiveBarState(
            conversationTitle = barConversation.title,
            agentName = barConversation.agentName,
            orbIndex = barConversation.agentId?.let { host.avatarStyleByAgentId[it] }
                ?: host.fallbackOrbIndex,
            status = nowActiveStatus(
                isThinking = host.thinkingConversationId == barConversation.id,
                isStreaming = if (barIsSelected) {
                    host.isStreamingReplySelected
                } else {
                    streamingId == barConversation.id
                },
                hasError = barIsSelected && chatState.errorMessage != null,
                isStopping = cancellingId == barConversation.id,
            ),
            backgroundWorkAgentName = host.thinkingConversationId
                ?.takeIf { it != barConversation.id }
                ?.let { tid -> chatState.conversations.firstOrNull { it.id == tid }?.agentName },
            avatarCompanionActive = host.avatarCompanionActive,
        ),
        actions = NowActiveBarActions(
            onOpenConversation = { actions.onOpenConversation(barConversation.id) },
            onJumpToBackgroundWork = {
                host.thinkingConversationId?.let(actions.onOpenConversation)
            },
            onAvatarCompanion = actions.onAvatarCompanion,
            // Scoped to the bar's pinned conversation so an unrelated
            // in-flight send can never be cancelled by mistake.
            onStopRun = { actions.onStopRun(barConversation.id) },
        ),
    )
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
                if (state.status == NowActiveStatus.Thinking ||
                    state.status == NowActiveStatus.Streaming ||
                    state.status == NowActiveStatus.Stopping
                ) {
                    StopRunButton(stopping = state.status == NowActiveStatus.Stopping, onClick = actions.onStopRun)
                }
                AvatarCompanionButton(
                    active = state.avatarCompanionActive,
                    onClick = actions.onAvatarCompanion,
                )
            }
        }
    }
}

@Composable
private fun StopRunButton(stopping: Boolean, onClick: () -> Unit) {
    BarIconButton(
        icon = Icons.Outlined.StopCircle,
        description = if (stopping) "Stopping run — click again to force stop" else "Stop run",
        tint = MaterialTheme.colorScheme.error,
        onClick = onClick,
    )
}

@Composable
private fun AvatarCompanionButton(active: Boolean, onClick: () -> Unit) {
    BarIconButton(
        icon = Icons.Outlined.Face,
        description = if (active) "Stop avatar companion" else "Avatar companion",
        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    )
}

@Composable
private fun BarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
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
