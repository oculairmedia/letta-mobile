package com.letta.mobile.channel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.notify.ApprovalAttentionMonitor
import com.letta.mobile.data.notify.AttentionFocus
import com.letta.mobile.data.notify.AttentionGate
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.repository.api.IAgentRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-bzvro.27 (F27): Android's binding of the shared approval notifications. It watches
 * the app-wide [ConversationRunRegistry] (the chat screens fold every runtime event into it) through
 * the shared [AttentionGate] and posts with [ApprovalNotificationPublisher]. The person counts as
 * looking only while the app is in the foreground with that conversation on screen.
 *
 * Process-scoped: [ChatPushService] runs [watch] for as long as it lives, which is what keeps the
 * process (and the chat that is waiting) alive in the background.
 */
@Singleton
class AndroidApprovalAttention @Inject constructor(
    private val runs: ConversationRunRegistry,
    private val prefs: AttentionPrefsRepository,
    private val tracker: CurrentConversationTracker,
    private val agentRepository: IAgentRepository,
    private val publisher: ApprovalNotificationPublisher,
) {
    private val gate = AttentionGate(prefs = { prefs.prefs.value }, focus = foregroundFocus(tracker))

    suspend fun watch() {
        publisher.ensureChannel()
        ApprovalAttentionMonitor(gate).watch(runs.runs) { event ->
            val agentName = agentRepository.agents.value.firstOrNull { it.id.value == event.agentId }?.name
            publisher.publish(event, agentName)
        }
    }
}

/** Looking at [conversationId] means the app is started and that conversation is the visible one. */
internal fun foregroundFocus(
    tracker: CurrentConversationTracker,
    appStarted: () -> Boolean = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
): AttentionFocus = AttentionFocus { conversationId -> appStarted() && tracker.current == conversationId }
