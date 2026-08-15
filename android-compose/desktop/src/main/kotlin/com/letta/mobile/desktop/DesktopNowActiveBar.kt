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
    /** Bring the active conversation on screen (identity block click). */
    val onOpenConversation: () -> Unit,
    /** Jump to the conversation doing background work (chip click). */
    val onJumpToBackgroundWork: () -> Unit,
    /** Toggle the desktop avatar companion (trailing control). */
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
    /** Select + reveal the given conversation (identity block and work chip). */
    val onOpenConversation: (String) -> Unit,
    val onAvatarCompanion: () -> Unit,
    /** Interrupt the given conversation's in-flight run. */
    val onStopRun: (String) -> Unit,
)

/** Result of pinning a conversation for the header identity block: which
 * conversation it is (so callers can scope actions to it) plus the state to
 * render. */
@Immutable
internal data class NowActiveBarPin(
    val conversationId: String,
    val state: NowActiveBarState,
)

/**
 * Derives the header identity block's pinned conversation and live status
 * from controller state: pinned to the conversation the user LAST PROMPTED
 * (sticky across browsing, like now-playing), falling back to the selection
 * until the first send. Returns null when there is no conversation at all —
 * callers should degrade to a generic title in that case.
 *
 * Pure by design (no Compose state reads) so the mapping is unit-testable
 * without a Compose UI test harness.
 */
internal fun deriveNowActiveBarPin(
    lastPromptedId: String?,
    streamingId: String?,
    cancellingId: String?,
    chatState: DesktopChatSurfaceState,
    host: NowActiveBarHostState,
): NowActiveBarPin? {
    val barConversation = lastPromptedId
        ?.let { id -> chatState.conversations.firstOrNull { it.id == id } }
        ?: chatState.selectedConversation
        ?: return null
    val barIsSelected = barConversation.id == chatState.selectedConversationId
    return NowActiveBarPin(
        conversationId = barConversation.id,
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
    )
}

/** Identity block state + click handlers ready for the header chrome to render. */
@Immutable
internal data class DesktopHeaderIdentity(
    val state: NowActiveBarState?,
    val actions: NowActiveBarActions,
)

private val NoopNowActiveBarActions = NowActiveBarActions(
    onOpenConversation = {},
    onJumpToBackgroundWork = {},
)

/**
 * State provider (no UI of its own): derives the pinned conversation and
 * live status from controller state, same as before, but now returns a
 * value instead of rendering the footer bar — the header chrome (see
 * [DesktopJewelWindow]) is the one place that renders it, letta-mobile-3arhe.1.
 */
@Composable
internal fun DesktopNowActiveBarHost(
    chatController: DesktopChatController,
    chatState: DesktopChatSurfaceState,
    host: NowActiveBarHostState,
    actions: NowActiveBarHostActions,
): DesktopHeaderIdentity {
    val lastPromptedId by chatController.lastPromptedConversationId.collectAsState()
    val streamingId by chatController.streamingConversationId.collectAsState()
    // letta-mobile-lgns8.19: an abort was sent but the terminal frame hasn't
    // landed — the run is still live, so the header says "stopping…" and the
    // stop button stays available as the local force-clear escape hatch.
    val cancellingId by chatController.cancellingConversationId.collectAsState()
    val pin = deriveNowActiveBarPin(lastPromptedId, streamingId, cancellingId, chatState, host)
        ?: return DesktopHeaderIdentity(state = null, actions = NoopNowActiveBarActions)
    return DesktopHeaderIdentity(
        state = pin.state,
        actions = NowActiveBarActions(
            onOpenConversation = { actions.onOpenConversation(pin.conversationId) },
            onJumpToBackgroundWork = {
                host.thinkingConversationId?.let(actions.onOpenConversation)
            },
            onAvatarCompanion = actions.onAvatarCompanion,
            // Scoped to the pinned conversation so an unrelated in-flight
            // send can never be cancelled by mistake.
            onStopRun = { actions.onStopRun(pin.conversationId) },
        ),
    )
}

/**
 * Leading identity block for the desktop window chrome: agent orb, then
 * conversation title stacked over agent name (+ live status), matching the
 * layout previously used by the footer now-playing bar. Clicking anywhere on
 * the block brings the pinned conversation on screen.
 */
@Composable
internal fun DesktopHeaderIdentityBlock(
    state: NowActiveBarState,
    actions: NowActiveBarActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = actions.onOpenConversation)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentOrb(index = state.orbIndex, size = 28.dp, cornerRadius = 7.dp)
        Column {
            Text(
                text = state.conversationTitle,
                style = MaterialTheme.typography.labelLarge,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.status != NowActiveStatus.Idle) {
                    StatusDot(state.status)
                    Text(
                        text = state.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.status == NowActiveStatus.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Trailing controls that used to sit at the end of the footer bar: the
 * background-work chip, the stop-run button (only while a run is live), and
 * the avatar-companion toggle. Rendered to the left of the native window
 * controls so they never collide with minimize/maximize/close.
 */
@Composable
internal fun DesktopHeaderTrailingControls(
    state: NowActiveBarState,
    actions: NowActiveBarActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
