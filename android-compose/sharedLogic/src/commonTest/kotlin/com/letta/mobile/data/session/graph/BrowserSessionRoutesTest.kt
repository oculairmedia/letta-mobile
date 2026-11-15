package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.backend.SessionRouteMatrix
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserSessionRoutesTest {
    @Test
    fun browserCapabilitiesFollowTheSharedMatrix() {
        val configs = listOf(
            LettaConfig(id = "blank", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = ""),
            LettaConfig(id = "direct", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "ws://localhost:4500"),
            LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = ""),
        )
        for (config in configs) {
            assertEquals(SessionRouteMatrix.bound(SessionPlatform.WEB, hostServedKind(config)), webSessionCapabilities(config).bound)
        }
    }

    @Test
    fun browserConnectionStatusPreservesBlankAndUnsupportedReasons() {
        val config = LettaConfig(id = "browser", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")
        assertEquals(BackendConnectionStatus.Unconfigured, unservedBrowserSessionStatus(config))
        assertNull(unservedBrowserSessionStatus(config.copy(serverUrl = "ws://localhost:4500")))
        assertEquals(
            BackendConnectionStatus.Failed("This backend has no route from a browser (a browser cannot run the on-device runtime)."),
            unservedBrowserSessionStatus(config.copy(mode = LettaConfig.Mode.LOCAL, serverUrl = "ws://localhost:4500")),
        )
    }
}
