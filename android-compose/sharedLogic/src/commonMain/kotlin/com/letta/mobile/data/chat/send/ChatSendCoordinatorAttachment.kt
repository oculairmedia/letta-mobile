package com.letta.mobile.data.chat.send

import com.letta.mobile.data.transport.AgentEventAttachment
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * letta-mobile-ztuog: a [ChatSendCoordinator]'s agent-keyed transport subscription.
 *
 * Only frames the bridge attributes to the coordinator's agent arrive. The subscription detaches
 * once another chat is selected and the coordinator has nothing in flight, and re-attaches with
 * its turn state intact when [select]ed again. It is released when the coordinator's scope ends.
 */
class ChatSendEventSubscription internal constructor(
    scope: CoroutineScope,
    private val attachment: AgentEventAttachment,
    handle: suspend (WsTimelineEvent) -> Unit,
) {
    init {
        scope.launch { attachment.deliveries.collect { handle(it) } }
        scope.coroutineContext[Job]?.invokeOnCompletion { attachment.release() }
    }

    /** This chat is the one on screen: every other chat detaches once its turns settle. */
    fun select() = attachment.select()

    /** Only the chat on screen sends, so a send claims the selection before its first frame. */
    internal fun claimForSend() = attachment.claimForSend()
}

/**
 * letta-mobile-sfex6 / letta-mobile-ztuog: the defensive foreign-agent gate.
 *
 * Delivery is agent-keyed, so a frame naming another agent should never reach a coordinator;
 * one that does means routing leaked, and is dropped here (with a WARN) before it can open a turn
 * entry or ingest into this agent's timeline — the cross-agent bleed where two agents share the
 * bare conversation id `default`. Frames without an agent id are scoped transitively: a
 * conversation entry only gains a turn id from a TurnStarted that passed this gate.
 */
internal fun WsTimelineEvent.isForeignTo(boundAgentId: String): Boolean {
    val eventAgentId = when (this) {
        is WsTimelineEvent.TurnStarted -> agentId
        is WsTimelineEvent.AgentUpdated -> agentId
        is WsTimelineEvent.MessageDelta -> agentId
        else -> null
    }
    if (eventAgentId == null || eventAgentId == boundAgentId) return false
    Telemetry.event(
        "AdminChatVM", "ws.event.foreignAgentDropped",
        "eventType" to (this::class.simpleName ?: ""),
        "eventAgentId" to eventAgentId,
        "boundAgentId" to boundAgentId,
        level = Telemetry.Level.WARN,
    )
    return true
}
