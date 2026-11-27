package com.letta.mobile.data.backend

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.FakeIrohAdminTransport
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.session.graph.HostAppServerClients
import com.letta.mobile.data.session.graph.HostRepositoryAdapters
import com.letta.mobile.data.session.graph.HostSessionBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop attaches to its own node for the bundled runtime exactly as to a node over Iroh, so its local session
 * serves the Iroh row: same routes, same divergences from Android, and the same node-session bindings.
 */
class DesktopNodeSessionParityTest {
    @Test
    fun theDesktopLocalRowEqualsItsIrohRow() {
        assertEquals(
            SessionRouteMatrix.bound(SessionPlatform.DESKTOP, BackendKind.IROH),
            SessionRouteMatrix.bound(SessionPlatform.DESKTOP, BackendKind.LOCAL_RUNTIME),
        )
    }

    @Test
    fun theDesktopLocalRowDivergesFromAndroidOnlyWhereTheIrohRowDoes() {
        fun desktopVersusAndroid(kind: BackendKind): Set<SessionRoute> = SessionRoute.entries.filter { route ->
            val onDesktop = route in SessionRouteMatrix.bound(SessionPlatform.DESKTOP, kind)
            onDesktop != (route in SessionRouteMatrix.bound(SessionPlatform.ANDROID, kind))
        }.toSet()

        assertEquals(desktopVersusAndroid(BackendKind.IROH), desktopVersusAndroid(BackendKind.LOCAL_RUNTIME))
        for (route in desktopVersusAndroid(BackendKind.LOCAL_RUNTIME)) {
            assertEquals(
                SessionRouteMatrix.DIVERGENCES[BackendKind.IROH to route],
                SessionRouteMatrix.DIVERGENCES[BackendKind.LOCAL_RUNTIME to route],
                "$route diverges for the same reason as over Iroh",
            )
        }
    }

    @Test
    fun onlyTheDesktopAttachesToANodeForTheBundledRuntime() {
        assertTrue(SessionRouteMatrix.attachesThroughNode(SessionPlatform.DESKTOP, BackendKind.LOCAL_RUNTIME))
        assertFalse(SessionRouteMatrix.attachesThroughNode(SessionPlatform.WEB, BackendKind.LOCAL_RUNTIME))
        assertFalse(SessionRouteMatrix.attachesThroughNode(SessionPlatform.DESKTOP, BackendKind.DIRECT_WS))
        assertTrue(SessionPlatform.entries.all { SessionRouteMatrix.attachesThroughNode(it, BackendKind.IROH) })
    }

    @Test
    fun theDesktopLocalSessionBindsLikeItsIrohSession() {
        val local = adapters(LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = ""))
        val iroh = adapters(LettaConfig(id = "iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = IROH_URL))

        assertEquals(iroh.capabilities.bound, local.capabilities.bound)
        assertTrue(local.servesIrohRelays, "the desktop's own node relays skills and slash commands as an Iroh node does")
        listOf(local, iroh).forEach { adapters -> adapters.closeables.forEach { it.close() } }
    }

    /** No App Server client is offered: a node session needs none. */
    private fun adapters(config: LettaConfig): HostRepositoryAdapters {
        val transport = FakeIrohAdminTransport()
        return HostRepositoryAdapters(
            HostSessionBinding(
                platform = SessionPlatform.DESKTOP,
                config = config,
                appServerClients = HostAppServerClients { null },
                channelTransport = transport,
                irohAgentDirectoryProvider = { IrohAdminRpcAgentDirectory(transport) },
            ),
        )
    }

    private companion object {
        const val IROH_URL = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501"
    }
}
