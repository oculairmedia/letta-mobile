package com.letta.mobile.cli.commands.mounts

import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.NoConversationViewers
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.mounts.upgrade.CanvasMountDeviceUpgrade
import com.letta.mobile.data.transport.appserver.AppServerClient
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `--enable-canvas-mounts` (letta-mobile-n2stx.5): off by default, and off means nothing registered or advertised. */
class CanvasMountWiringTest {
    private val productState: Path = Files.createTempDirectory("canvas-mount-wiring")
    private val keyFile = productState.resolve("iroh.key").toString()
    private val records = HostCanvasRecords(InMemoryHostCanvasDirectory(), InMemoryCanvasRelayStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun withoutTheFlagNothingIsBuiltRegisteredOrAdvertised() = runBlocking {
        val wiring = CanvasMountWiring.of(CanvasMountOptions(enabled = false, secretKeyFile = keyFile), productState, records, scope)
        assertNull(wiring)
        val router = router(wiring?.routes(IrohAuthPolicy.PairedPeersOnly, client(), NoConversationViewers))
        assertTrue(router.registeredMethods.none { it in CanvasMountWire.METHODS })
        assertFalse(CanvasMountWire.CAPABILITY in router.featureCapabilities)
        assertFalse(Files.exists(productState.resolve("canvas-mounts")))
    }

    @Test
    fun withTheFlagASecureHostRegistersEveryRouteAndAdvertisesTheCapability() = runBlocking {
        val wiring = enabled()
        val router = router(wiring.routes(IrohAuthPolicy.PairedPeersOnly, client(), NoConversationViewers))
        assertTrue(router.registeredMethods.containsAll(CanvasMountWire.METHODS))
        assertTrue(CanvasMountWire.CAPABILITY in router.featureCapabilities)
    }

    @Test
    fun withTheFlagTheAnonymousPolicyOrAMissingAppServerStillRegistersNothing() = runBlocking {
        val wiring = enabled()
        val insecure = router(wiring.routes(IrohAuthPolicy.InsecureAnonymousForTestOnly, client(), NoConversationViewers))
        val noAppServer = wiring.routes(IrohAuthPolicy.PairedPeersOnly, null, NoConversationViewers)
        assertTrue(insecure.registeredMethods.none { it in CanvasMountWire.METHODS })
        assertFalse(CanvasMountWire.CAPABILITY in insecure.featureCapabilities)
        assertNull(noAppServer)
    }

    @Test
    fun theFlagNeedsAStableHostIdentity() {
        val failure = assertThrows<IllegalArgumentException> {
            runBlocking { CanvasMountWiring.of(CanvasMountOptions(enabled = true, secretKeyFile = null), productState, records, scope) }
        }
        assertEquals(true, failure.message?.contains("--iroh-secret-key-file"))
    }

    @Test
    fun thePairedDeviceUpgradeRunsOnlyWithTheFlagAndAPairingStore() {
        val peers = productState.resolve("paired-peers.json")
        val chatBefore = IrohPeerCapabilities.CHAT_ROLE
        FilePairedPeerStore(peers).save(PairedPeer("phone", "phone", pairedAtMs = 1L, capabilities = chatBefore))

        assertNull(upgradePairedDevicesForCanvasMounts(CanvasMountOptions(enabled = false, secretKeyFile = keyFile), peers))
        assertEquals(chatBefore, FilePairedPeerStore(peers).get("phone")?.capabilities)
        assertNull(upgradePairedDevicesForCanvasMounts(CanvasMountOptions(enabled = true, secretKeyFile = keyFile), null))

        val enabled = CanvasMountOptions(enabled = true, secretKeyFile = keyFile)
        assertEquals(CanvasMountDeviceUpgrade.Outcome.Applied(upgraded = 1), upgradePairedDevicesForCanvasMounts(enabled, peers))
        assertEquals(IrohPeerCapabilities.CHAT_ROLE + IrohPeerCapabilities.MOUNT_CAPABILITIES, FilePairedPeerStore(peers).get("phone")?.capabilities)
        assertEquals(CanvasMountDeviceUpgrade.Outcome.AlreadyApplied, upgradePairedDevicesForCanvasMounts(enabled, peers))
    }

    private suspend fun enabled(): CanvasMountWiring =
        checkNotNull(CanvasMountWiring.of(CanvasMountOptions(enabled = true, secretKeyFile = keyFile), productState, records, scope))

    private fun router(routes: AdminRpcRoutes?): AdminRpcRouter =
        AdminRpcRegistry.buildRouter(services = AdminRpcHostServices(canvasMountRoutes = routes))

    private fun client(): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader,
        arrayOf(AppServerClient::class.java),
    ) { _, method, _ -> error("Unexpected App Server call: ${method.name}") } as AppServerClient
}
