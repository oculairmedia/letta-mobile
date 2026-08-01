package com.letta.mobile.data.controller.fanout

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerChannelAccount
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.23 landmine 2 — `channel_accounts_list_response` carries the
 * plugin account config VERBATIM (Matrix `accessToken` / `syncAccessToken` in
 * cleartext). These are frames the CONTROLLER initiates, so they must stay
 * controller-internal and must never reach an Iroh viewer's subscriber channel.
 *
 * FAIL-ON-REVERT: adding the channel frames to `isServerInitiatedControlFrame()`
 * — or otherwise broadcasting unscoped frames to all subscribers — makes this
 * test fail.
 */
class ChannelFrameFanoutIsolationTest {

    @Test
    fun channelResponseFramesAreNotFannedOutToSubscribers() = runTest {
        val fanout = RuntimeEventFanout()
        val (_, events) = fanout.subscribe(AgentId("agent-1"), ConversationId("conv-1"))

        fanout.route(channelAccountsFrame())
        fanout.route(channelStartFrame())
        fanout.route(channelsListFrame())

        events.test { expectNoEvents() }
        assertEquals(0, fanout.pendingControlFrameCount(), "credential-bearing frames must not be buffered either")
    }

    private fun channelAccountsFrame() = received(
        AppServerInboundFrame.ChannelAccountsListResponse(
            requestId = "r1",
            success = true,
            channelId = "matrix",
            accounts = listOf(
                AppServerChannelAccount(
                    channelId = "matrix",
                    accountId = "lettabot",
                    enabled = true,
                    configured = true,
                    running = true,
                    config = buildJsonObject { put("accessToken", "syt_SECRET") },
                ),
            ),
        ),
    )

    private fun channelStartFrame() = received(
        AppServerInboundFrame.ChannelStartResponse(requestId = "r2", success = true),
    )

    private fun channelsListFrame() = received(
        AppServerInboundFrame.ChannelsListResponse(requestId = "r3", success = true),
    )

    private fun received(frame: AppServerInboundFrame) = AppServerReceivedFrame(
        channel = AppServerChannel.Control,
        frame = frame,
        raw = buildJsonObject { put("type", frame.type) },
    )
}
