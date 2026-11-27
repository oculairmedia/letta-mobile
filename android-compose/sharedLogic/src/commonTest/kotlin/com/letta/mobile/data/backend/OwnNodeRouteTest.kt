package com.letta.mobile.data.backend

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the window's backend is the host's own node: only a LOCAL config without the developer App Server override
 * is. Local-only controls follow that, not whether the bundled runtime happens to run in the background.
 */
class OwnNodeRouteTest {
    private val local = LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "")
    private val remoteNode = LettaConfig(id = "remote", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL)
    private val route = OwnNodeRoute()

    @Test
    fun onlyALocalConfigWithoutTheOverrideIsTheHostsOwnNode() {
        assertTrue(route.attaches(local, appServerOverride = null))
        assertFalse(route.attaches(local, appServerOverride = OVERRIDE), "the override dials an App Server directly")
        assertFalse(route.attaches(remoteNode, appServerOverride = null))
        assertFalse(route.attaches(null, appServerOverride = null))
    }

    @Test
    fun aRemoteNodeIsNeverLocalWhileTheBundledRuntimeRunsInTheBackground() {
        route.publish(remoteNode, appServerOverride = null)
        val onOwnNode = route.attached.value

        assertFalse(onOwnNode)
        assertFalse(OwnNodeRoute.filesAreLocal(runtimeActive = true, onOwnNode = onOwnNode, hasDirectClient = false))
    }

    @Test
    fun theHostsOwnNodeWithItsRuntimeUpIsLocal() {
        route.publish(local, appServerOverride = null)
        val onOwnNode = route.attached.value

        assertTrue(OwnNodeRoute.filesAreLocal(runtimeActive = true, onOwnNode = onOwnNode, hasDirectClient = false))
        assertFalse(OwnNodeRoute.filesAreLocal(runtimeActive = false, onOwnNode = onOwnNode, hasDirectClient = false))
        assertFalse(
            OwnNodeRoute.filesAreLocal(runtimeActive = true, onOwnNode = onOwnNode, hasDirectClient = true),
            "a directly dialled App Server, which the workspace route prefers, may be another machine",
        )
    }

    @Test
    fun theGraphReadsALocalConfigUnderTheOverrideAsTheAppServerItNames() {
        val routed = route.graphConfig(local, OVERRIDE)

        assertEquals(LettaConfig.Mode.SELF_HOSTED, routed?.mode)
        assertEquals(OVERRIDE, routed?.serverUrl)
        assertEquals(local.id, routed?.id)
        assertEquals(local, route.graphConfig(local, null))
        assertEquals(remoteNode, route.graphConfig(remoteNode, OVERRIDE), "only a LOCAL config is rerouted")
    }

    private companion object {
        const val OVERRIDE = "ws://127.0.0.1:4500"
        const val IROH_URL = "iroh://node-ticket"
    }
}
