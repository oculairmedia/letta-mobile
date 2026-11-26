package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.HealthAdminHandlers
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodeSession
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assume.assumeTrue

/**
 * A [NodeSession] over [IrohNodeCarrier] against an Iroh host as it runs today ([IrohNodeEndpoint] serving through
 * its own connection handler, untouched): the carrier changes no frame, so the host accepts the session's `auth`,
 * answers `admin_rpc` on call lanes, refuses a wrong token, and sees a detach as an ordinary close.
 *
 * Live QUIC, so opt-in: `./gradlew :sharedLogic:jvmTest -DrunIrohLiveE2E=true --tests '<this class>'`.
 */
class IrohNodeCarrierCompatibilityTest {
    @BeforeTest
    fun requireOptIn() {
        assumeTrue("Live Iroh requires -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    @Test
    fun aNodeSessionOverTheIrohCarrierTalksToAnExistingHostUnchanged() = onHost { host, carrier, scope ->
        val address = NodeAddress("iroh://${host.ticketString()}")
        val session = NodeSession.open(carrier, NodeOpenRequest(address, NodeCredential.Token(TOKEN)), scope)

        assertEquals(host.nodeIdHex(), session.node.value, "the carrier reports the host's verified endpoint id")
        assertTrue(session.capabilities.supports(NodeCarrierWire.FRAME_PART_CAPABILITY), "host capabilities: ${session.capabilities}")

        val health = session.adminRpc("health.check", null)
        assertEquals(NodeCarrierWire.ADMIN_RPC_RESPONSE, health.type)
        assertEquals(true, (health.json[NodeCarrierWire.SUCCESS] as? JsonPrimitive)?.booleanOrNull, "health.check: ${health.json}")
        assertTrue((health.json[NodeCarrierWire.RESULT] as? JsonObject)?.containsKey("status") == true, "health.check: ${health.json}")

        val unknown = session.adminRpc("no.such.method", null)
        assertEquals(false, (unknown.json[NodeCarrierWire.SUCCESS] as? JsonPrimitive)?.booleanOrNull, "unknown method: ${unknown.json}")

        session.close()
        assertEquals(NodeLinkClosure.NORMAL, session.closure.value)
    }

    @Test
    fun theHostRefusesAWrongTokenOverTheIrohCarrier() = onHost { host, carrier, scope ->
        val address = NodeAddress("iroh://${host.ticketString()}")
        val failure = runCatching {
            NodeSession.open(carrier, NodeOpenRequest(address, NodeCredential.Token("wrong-$TOKEN")), scope)
        }.exceptionOrNull()
        assertTrue((failure as? NodeCarrierException)?.failure is NodeCarrierFailure.AuthRejected, "open failed with $failure")
    }

    private fun onHost(body: suspend (IrohNodeEndpoint, IrohNodeCarrier, CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = IrohNodeEndpoint(
            scope = scope,
            authPolicy = IrohAuthPolicy.BearerToken(TOKEN),
        )
        HealthAdminHandlers.register(host.adminRpcRouter, FakeAppServerController())
        val shell = bindLoopbackEndpoint()
        try {
            withTimeout(TEST_TIMEOUT) {
                host.create()
                host.start(FakeAppServerController())
                body(host, IrohNodeCarrier(shell, scope, connectTimeout = DIAL_TIMEOUT), scope)
            }
        } finally {
            withTimeoutOrNull(TEARDOWN_TIMEOUT) {
                shell.release()
                host.shutdown()
            }
            scope.cancel()
        }
    }

    private companion object {
        /** A fresh bearer token per run; nothing outside this test knows it. */
        val TOKEN: String = UUID.randomUUID().toString().replace("-", "")
        val TEST_TIMEOUT = 30.seconds
        val DIAL_TIMEOUT = 10.seconds
        val TEARDOWN_TIMEOUT = 10.seconds
    }
}
