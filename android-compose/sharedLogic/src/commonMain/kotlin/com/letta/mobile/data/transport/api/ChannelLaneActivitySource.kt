package com.letta.mobile.data.transport.api

import com.letta.mobile.data.lanes.LaneActivitySource
import com.letta.mobile.data.lanes.LaneArrival
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Adapts the session channel's persisted inbox pushes while the host advertises support. */
internal fun channelLaneActivitySource(transport: IChannelTransport): LaneActivitySource = object : LaneActivitySource {
    override val available: Boolean get() = ServerFrame.ConversationActivity.CAPABILITY in transport.hostCapabilities

    override fun arrivals() = transport.events
        .filterIsInstance<ServerFrame.ConversationActivity>()
        .filter { available }
        .filter { it.milestone == "persisted" }
        .map { LaneArrival(it.agentId, it.conversationId, it.sourceMessageId) }
}
