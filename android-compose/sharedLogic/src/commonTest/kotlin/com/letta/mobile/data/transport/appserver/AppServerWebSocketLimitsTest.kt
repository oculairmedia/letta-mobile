package com.letta.mobile.data.transport.appserver

import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-lgns8.21.7: the App Server WS clients must never run with Ktor's
 * unbounded default frame size.
 */
class AppServerWebSocketLimitsTest {

    @Test
    fun ktorDefaultFrameSizeIsEffectivelyUnbounded() {
        // Guards the premise: Ktor's default is Int.MAX_VALUE (~2 GiB), i.e. no
        // usable ceiling. If Ktor ever ships a sane default this test fails and
        // the constant can be revisited.
        assertEquals(Int.MAX_VALUE.toLong(), WebSockets.Config().maxFrameSize)
        assertTrue(AppServerWebSocketLimits.MAX_FRAME_BYTES < WebSockets.Config().maxFrameSize)
    }

    @Test
    fun appServerFrameLimitIsApplied() {
        val config = WebSockets.Config()

        config.applyAppServerFrameLimits()

        assertEquals(AppServerWebSocketLimits.MAX_FRAME_BYTES, config.maxFrameSize)
    }

    @Test
    fun appServerFrameLimitLeavesHeadroomOverTheServeSideCap() {
        val serveSideFrameCap = 1L * 1024 * 1024
        assertTrue(
            AppServerWebSocketLimits.MAX_FRAME_BYTES >= serveSideFrameCap * 8,
            "ceiling must comfortably exceed legitimate frames",
        )
        assertTrue(
            AppServerWebSocketLimits.MAX_FRAME_BYTES < Long.MAX_VALUE,
            "ceiling must remain bounded",
        )
    }
}
