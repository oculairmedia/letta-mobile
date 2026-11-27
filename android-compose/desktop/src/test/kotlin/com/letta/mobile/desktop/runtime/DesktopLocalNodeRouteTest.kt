package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the window's backend is the desktop's own node: only a LOCAL config without the developer App Server override
 * is. Local-only controls follow that, not whether the bundled runtime happens to run in the background.
 */
class DesktopLocalNodeRouteTest {
    private val local = LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "")
    private val remoteNode = LettaConfig(id = "remote", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL)

    @AfterTest
    fun tearDown() = DesktopLocalNodeRoute.publish(null, null)

    @Test
    fun onlyALocalConfigWithoutTheOverrideIsTheDesktopsOwnNode() {
        assertTrue(DesktopLocalNodeRoute.attaches(local, appServerOverride = null))
        assertFalse(DesktopLocalNodeRoute.attaches(local, appServerOverride = OVERRIDE), "the override dials an App Server directly")
        assertFalse(DesktopLocalNodeRoute.attaches(remoteNode, appServerOverride = null))
        assertFalse(DesktopLocalNodeRoute.attaches(null, appServerOverride = null))
    }

    @Test
    fun aRemoteNodeIsNeverLocalWhileTheBundledRuntimeRunsInTheBackground() {
        DesktopLocalNodeRoute.publish(remoteNode, appServerOverride = null)
        val onLocalNode = DesktopLocalNodeRoute.attached.value

        assertFalse(onLocalNode)
        assertFalse(DesktopLocalNodeRoute.filesAreLocal(runtimeActive = true, onLocalNode = onLocalNode, hasDirectClient = false))
    }

    @Test
    fun theDesktopsOwnNodeWithItsRuntimeUpIsLocal() {
        DesktopLocalNodeRoute.publish(local, appServerOverride = null)
        val onLocalNode = DesktopLocalNodeRoute.attached.value

        assertTrue(DesktopLocalNodeRoute.filesAreLocal(runtimeActive = true, onLocalNode = onLocalNode, hasDirectClient = false))
        assertFalse(DesktopLocalNodeRoute.filesAreLocal(runtimeActive = false, onLocalNode = onLocalNode, hasDirectClient = false))
        assertFalse(
            DesktopLocalNodeRoute.filesAreLocal(runtimeActive = true, onLocalNode = onLocalNode, hasDirectClient = true),
            "a directly dialled App Server, which the workspace route prefers, may be another machine",
        )
    }

    @Test
    fun theGraphReadsALocalConfigUnderTheOverrideAsTheAppServerItNames() {
        val routed = DesktopLocalNodeRoute.graphConfig(local, OVERRIDE)

        assertEquals(LettaConfig.Mode.SELF_HOSTED, routed?.mode)
        assertEquals(OVERRIDE, routed?.serverUrl)
        assertEquals(local.id, routed?.id)
        assertEquals(local, DesktopLocalNodeRoute.graphConfig(local, null))
        assertEquals(remoteNode, DesktopLocalNodeRoute.graphConfig(remoteNode, OVERRIDE), "only a LOCAL config is rerouted")
    }

    private companion object {
        const val OVERRIDE = "ws://127.0.0.1:4500"
        const val IROH_URL = "iroh://node-ticket"
    }
}
