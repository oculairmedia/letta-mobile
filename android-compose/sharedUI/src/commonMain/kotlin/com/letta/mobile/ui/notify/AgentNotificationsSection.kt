package com.letta.mobile.ui.notify

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** letta-mobile-bzvro.27: test tags of the per-agent notification section. */
object AgentNotificationsTags {
    const val SECTION = "agent-notifications-section"
    const val SWITCH = "agent-notifications-switch"
    const val CAPTION = "agent-notifications-caption"
    const val APPROVALS_SWITCH = "approval-notifications-switch"
}

/**
 * letta-mobile-bzvro.27: the app's [AttentionPrefsRepository], for surfaces the host does not wire
 * by hand (Android's drawer card, desktop settings). Null where the host posts no notifications.
 */
val LocalAttentionPrefs = staticCompositionLocalOf<AttentionPrefsRepository?> { null }

/** The section's words, in one place. */
object AgentNotificationsStrings {
    const val TITLE = "Notifications"
    const val TOGGLE = "Notify me about this agent"
    const val ON_CAPTION = "Approval requests and finished replies notify you while you're away."
    const val MUTED_CAPTION = "Muted. This agent won't notify you."
    const val APPROVALS_OFF_CAPTION = "Approval notifications are off in settings; finished replies still notify you."
    const val APPROVALS_SWITCH = "Notify me when an agent needs approval"
}

/**
 * letta-mobile-bzvro.27 (F27): one switch that mutes or unmutes [agentId]'s notifications, read and
 * written through the shared [AttentionPrefsRepository]. The caption says what the setting means
 * now; its height change animates unless the person prefers reduced motion.
 */
@Composable
fun AgentNotificationsSection(prefs: AttentionPrefsRepository, agentId: String, modifier: Modifier = Modifier) {
    val current by prefs.prefs.collectAsState()
    val notifying = !current.isMuted(agentId)
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)))
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
            .testTag(AgentNotificationsTags.SECTION),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(text = AgentNotificationsStrings.TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text(text = AgentNotificationsStrings.TOGGLE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = notifying,
                onCheckedChange = { notify -> prefs.setAgentMuted(agentId, muted = !notify) },
                modifier = Modifier
                    .testTag(AgentNotificationsTags.SWITCH)
                    .semantics { contentDescription = AgentNotificationsStrings.TOGGLE },
            )
        }
        Text(
            text = notificationsCaption(notifying, current.approvalNotificationsEnabled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(AgentNotificationsTags.CAPTION),
        )
    }
}

/** The global switch for approval notifications, for a host's notification settings. */
@Composable
fun ApprovalNotificationsSwitch(prefs: AttentionPrefsRepository, modifier: Modifier = Modifier) {
    val current by prefs.prefs.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(text = AgentNotificationsStrings.APPROVALS_SWITCH, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = current.approvalNotificationsEnabled,
            onCheckedChange = prefs::setApprovalNotificationsEnabled,
            modifier = Modifier
                .testTag(AgentNotificationsTags.APPROVALS_SWITCH)
                .semantics { contentDescription = AgentNotificationsStrings.APPROVALS_SWITCH },
        )
    }
}

internal fun notificationsCaption(notifying: Boolean, approvalsEnabled: Boolean): String = when {
    !notifying -> AgentNotificationsStrings.MUTED_CAPTION
    !approvalsEnabled -> AgentNotificationsStrings.APPROVALS_OFF_CAPTION
    else -> AgentNotificationsStrings.ON_CAPTION
}
