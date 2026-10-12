package com.letta.mobile.ui.shell.nowactive

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
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.runtime.NowActiveStatus
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-ckmub.3.14: the now-active identity — the conversation the user last prompted
 * (sticky across browsing, like now-playing), its agent and its live [status], plus the agent
 * working in another conversation, if any. Platforms derive it from their activity state.
 */
@Immutable
data class NowActiveBarState(
    val conversationTitle: String,
    val agentName: String,
    val agentId: String? = null,
    val status: NowActiveStatus,
    /** Agent working in a conversation OTHER than the pinned one, if any. */
    val backgroundWorkAgentName: String? = null,
)

@Immutable
data class NowActiveBarActions(
    /** Bring the pinned conversation on screen (identity click). */
    val onOpenConversation: () -> Unit,
    /** Jump to the conversation doing background work (chip click). */
    val onJumpToBackgroundWork: () -> Unit,
    /** Interrupt the pinned conversation's in-flight run (stop button). */
    val onStopRun: () -> Unit = {},
) {
    companion object {
        val None = NowActiveBarActions(onOpenConversation = {}, onJumpToBackgroundWork = {})
    }
}

object NowActiveBarTags {
    const val IDENTITY = "now_active_identity"
    const val STOP = "now_active_stop"
    const val BACKGROUND_WORK = "now_active_background_work"
}

/** A run is live (and so stoppable) while the agent thinks, streams or is being stopped. */
val NowActiveStatus.isLiveRun: Boolean
    get() = this == NowActiveStatus.Thinking || this == NowActiveStatus.Streaming || this == NowActiveStatus.Stopping

/** The words under the agent name; empty while idle. */
val NowActiveStatus.label: String
    get() = when (this) {
        NowActiveStatus.Idle -> ""
        NowActiveStatus.Thinking -> "thinking…"
        NowActiveStatus.Streaming -> "responding…"
        NowActiveStatus.Stopping -> "stopping…"
        NowActiveStatus.Error -> "needs attention"
    }

/**
 * The header's leading identity block: the agent's avatar, then the conversation title stacked
 * over the agent name and live status. Clicking anywhere brings the pinned conversation on screen.
 */
@Composable
fun NowActiveIdentity(state: NowActiveBarState, actions: NowActiveBarActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .clickable(role = Role.Button, onClick = actions.onOpenConversation)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs)
            .testTag(NowActiveBarTags.IDENTITY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        AgentAvatar(
            agentId = state.agentId,
            name = state.agentName,
            size = LettaDimens.Orb.md,
            cornerRadius = LettaDimens.Radius.sm,
        )
        Column {
            Text(
                text = state.conversationTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AgentStatusLine(state)
        }
    }
}

@Composable
private fun AgentStatusLine(state: NowActiveBarState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = state.agentName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.status == NowActiveStatus.Idle) return@Row
        StatusDot(state.status)
        Text(
            text = state.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(state.status),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The header's trailing run controls: the background-work chip and the stop-run button (only
 * while a run is live).
 */
@Composable
fun NowActiveRunControls(state: NowActiveBarState, actions: NowActiveBarActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        state.backgroundWorkAgentName?.let { name ->
            BackgroundWorkChip(agentName = name, onClick = actions.onJumpToBackgroundWork)
        }
        if (state.status.isLiveRun) {
            StopRunButton(stopping = state.status == NowActiveStatus.Stopping, onClick = actions.onStopRun)
        }
    }
}

@Composable
private fun StopRunButton(stopping: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(LettaDimens.Control.iconButton)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(NowActiveBarTags.STOP),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.StopCircle,
            contentDescription = if (stopping) "Stopping run — click again to force stop" else "Stop run",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    }
}

@Composable
private fun statusColor(status: NowActiveStatus): Color =
    if (status == NowActiveStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

@Composable
private fun StatusDot(status: NowActiveStatus) {
    val transition = rememberInfiniteTransition(label = "nowActiveDot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(STATUS_PULSE_MS), RepeatMode.Reverse),
        label = "nowActiveDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(LettaDimens.Space.sm)
            .background(statusColor(status).copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun BackgroundWorkChip(agentName: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag(NowActiveBarTags.BACKGROUND_WORK),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
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

private const val STATUS_PULSE_MS = 650
