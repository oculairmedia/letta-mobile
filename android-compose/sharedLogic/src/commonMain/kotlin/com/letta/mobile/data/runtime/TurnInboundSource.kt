package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chooses fanout-router vs direct client events for a turn's inbound stream.
 * Kept outside [AppServerTurnEngine] so fanout wiring does not add another
 * cohesion responsibility to that hotspot.
 */
internal class TurnInboundSource(
    private val client: AppServerClient,
    private val eventRouter: AppServerRuntimeEventRouter?,
) {
    /** letta-mobile-by4vm.9: the router's session link; null on the direct path, which never reconnects. */
    val sessionLink: StateFlow<RuntimeSessionLink>? get() = eventRouter?.sessionLink

    fun subscribe(
        scope: AppServerRuntimeScope,
    ): Pair<String?, Flow<AppServerReceivedFrame>> {
        val router = eventRouter ?: return null to client.events
        val (subId, flow) = router.subscribe(
            AgentId(scope.agentId),
            ConversationId(scope.conversationId),
        )
        return subId to flow
    }

    fun unsubscribe(subscriberId: String?) {
        val id = subscriberId ?: return
        eventRouter?.unsubscribe(id)
    }
}
