package com.letta.mobile.data.transport.api

import com.letta.mobile.data.lanes.LaneActivitySource
import com.letta.mobile.data.lanes.LaneArrival
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.flow.mapNotNull

/** Adapts the session channel's persisted inbox pushes while the host advertises support. */
internal fun channelLaneActivitySource(transport: IChannelTransport): LaneActivitySource = object : LaneActivitySource {
    override val available: Boolean get() = ServerFrame.ConversationActivity.CAPABILITY in transport.hostCapabilities

    override fun arrivals() = transport.events.mapNotNull { frame ->
        if (available && frame is ServerFrame.ConversationActivity && frame.milestone == "persisted") {
            LaneArrival(frame.agentId, frame.conversationId, frame.sourceMessageId)
        } else {
            null
        }
    }
}
