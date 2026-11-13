package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.secrets.host.SecretItemScope
import com.letta.mobile.data.secrets.host.TestKeystoreProvider
import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The routes, the capability and the tool exist only on a secure JVM host with a keystore, and all together. */
class SecretRequestRegistrationTest {
    private val secure: List<IrohAuthPolicy> = listOf(
        IrohAuthPolicy.PairedPeersOnly,
        IrohAuthPolicy.BearerToken("t".repeat(MIN_TOKEN)),
        IrohAuthPolicy.PeerAllowlist(setOf(HUMAN_ENDPOINT)),
    )

    private fun feature(policy: IrohAuthPolicy, keystore: TestKeystoreProvider?): SecretRequestFeature? =
        SecretRequestFeature.forHost(policy, SecretRequestStorage(keystore, InMemorySecretRequestJournal()), SecretItemScope.PerAgent)

    private fun routerWith(feature: SecretRequestFeature?): AdminRpcRouter =
        AdminRpcRegistry.buildRouter(services = AdminRpcHostServices(secretRequestRoutes = feature?.routes))

    @Test
    fun anAnonymousHostOrOneWithoutAKeystoreBuildsNothingAndAdvertisesNothing() {
        val withheld = listOf(
            feature(IrohAuthPolicy.InsecureAnonymousForTestOnly, TestKeystoreProvider()),
            feature(IrohAuthPolicy.PairedPeersOnly, null),
            feature(IrohAuthPolicy.InsecureAnonymousForTestOnly, null),
        )
        assertTrue(withheld.all { it == null }, "no feature, so no routes and no tool to offer")
        val router = routerWith(null)
        assertTrue(router.registeredMethods.none { it in SecretRequestRouteWire.METHODS })
        assertTrue(SecretRequestRouteWire.CAPABILITY !in router.featureCapabilities)
    }

    @Test
    fun routesHandedToAnAnonymousEndpointStillRegisterNothing() {
        val handlers = SecretRouteHarness().feature.handlers
        val router = AdminRpcRouter()
        SecretRequestRoutes(IrohAuthPolicy.InsecureAnonymousForTestOnly, handlers).register(router)
        assertEquals(0, router.methodCount)
        assertTrue(router.featureCapabilities.isEmpty())
    }

    /**
     * [SecretRequestFeature.forHost] cannot see the endpoint, so the policy it is given could be
     * wrong. The backstop is per call: a session of an anonymous endpoint is authenticated and holds
     * admin.full, but is never a paired device, so routes built for a secure policy still refuse it.
     */
    @Test
    fun routesBuiltForASecurePolicyStillRefuseEverySessionOfAnAnonymousEndpoint() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val anonymous = controlConnection(harness, IrohAuthPolicy.InsecureAnonymousForTestOnly).requestContext()
        assertTrue(anonymous.authenticated && IrohPeerCapabilities.ADMIN_FULL in anonymous.grantedCapabilities)
        assertFalse(anonymous.pairedDevice)
        for (route in SecretRequestRoute.entries.filter { it.side == SecretRouteSide.HUMAN }) {
            val params = when (route) {
                SecretRequestRoute.LIST -> null
                SecretRequestRoute.SUBMIT -> submitParams(id, TestSecret("anonymous-session"))
                else -> idParams(id)
            }
            assertEquals(refusalEnvelope("forbidden"), harness.dispatch(route, params, anonymous), route.method)
        }
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun aSecureHostWithAKeystoreRegistersAllSixRoutesTheCapabilityAndTheTool() {
        for (policy in secure) {
            val built = assertNotNull(feature(policy, TestKeystoreProvider()), policy.toString())
            val router = routerWith(built)
            assertEquals(SecretRequestRouteWire.METHODS, router.registeredMethods.filter { it.startsWith("secret.request") }.toSet())
            assertTrue(SecretRequestRouteWire.CAPABILITY in router.featureCapabilities)
            assertEquals(listOf(SecretRequestTool.NAME), built.hostTools.map { it.name })
        }
    }

    @Test
    fun theRouteTableIsTheContractsMethodTable() {
        assertEquals(SecretRequestRouteWire.METHODS, SecretRequestRoute.entries.map { it.method }.toSet())
        val agentSide = SecretRequestRoute.entries.filter { it.side == SecretRouteSide.AGENT }.map { it.method }.toSet()
        assertEquals(SecretRequestRouteWire.AGENT_METHODS, agentSide)
        assertNull(SecretRequestRoute.of("secret.apply"))
    }

    @Test
    fun theGateMapsEachSideToItsOwnCapabilityGrantedToNoRole() {
        SecretRequestRouteWire.HUMAN_METHODS.forEach {
            assertEquals(IrohPeerCapabilities.SECRET_REQUEST_ANSWER, IrohPeerCapabilities.forAdminMethod(it))
        }
        SecretRequestRouteWire.AGENT_METHODS.forEach {
            assertEquals(IrohPeerCapabilities.SECRET_REQUEST_AGENT, IrohPeerCapabilities.forAdminMethod(it))
        }
        val granted = IrohPeerCapabilities.ROLES.values.flatten().toSet()
        assertTrue(IrohPeerCapabilities.SECRET_REQUEST_ANSWER !in granted)
        assertTrue(IrohPeerCapabilities.SECRET_REQUEST_AGENT !in granted)
        val desktop = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE
        assertTrue(SecretRequestRouteWire.METHODS.none { IrohPeerCapabilities.isAllowed(desktop, IrohPeerCapabilities.forAdminMethod(it)) })
    }

    private companion object {
        const val MIN_TOKEN = 64
    }
}
