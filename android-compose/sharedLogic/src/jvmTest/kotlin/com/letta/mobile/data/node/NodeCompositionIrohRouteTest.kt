package com.letta.mobile.data.node

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.node.carrier.NodeAddress
import com.letta.mobile.data.node.carrier.NodeCarrierException
import com.letta.mobile.data.node.carrier.NodeCarrierFailure
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeCredential
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodeOpenRequest
import com.letta.mobile.data.node.carrier.NodeSession
import com.letta.mobile.data.node.carrier.iroh.IrohNodeCarrier
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assume.assumeTrue

/**
 * The production wiring, end to end over real QUIC: an endpoint built by [NodeComposition] (so its App Server
 * connections go through the composition's own `appServerLinks`, the Iroh carrier route), its admin router installed
 * by the composition and the node started by it, dialled by [IrohNodeCarrier] with a [NodeSession]. Nothing in the
 * test supplies the route itself.
 *
 * Live QUIC, so opt-in: `./gradlew :sharedLogic:jvmTest -DrunIrohLiveE2E=true --tests '<this class>'`.
 */
class NodeCompositionIrohRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateDir: File = Files.createTempDirectory("node-composition-route").toFile()
    private val token: String = UUID.randomUUID().toString().replace("-", "")

    private val node = NodeComposition(
        config = NodeConfig(
            modelExposureFile = File(stateDir, "model-exposure.json").path,
            requestTimeoutMs = 1_000,
            endpoint = NodeEndpointConfig(bindAddr = "127.0.0.1:0"),
        ),
        ports = NodePorts(
            httpClients = JvmAppServerHttpClients,
            log = object : NodeLog {
                override fun info(line: String) = Unit

                override fun error(line: String) = Unit
            },
        ),
        scope = scope,
    )

    @BeforeTest
    fun requireOptIn() {
        assumeTrue("Live Iroh requires -DrunIrohLiveE2E=true", System.getProperty("runIrohLiveE2E") == "true")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        stateDir.deleteRecursively()
    }

    @Test
    fun aSessionReachesTheNodeThroughTheCompositionsOwnIrohRoute() = runBlocking {
        val endpoint = node.endpoint(NodeEndpointParts(authPolicy = IrohAuthPolicy.BearerToken(token)))
        val connection = node.connect(appServer = null, tools = ExternalToolRegistry.factoryDefault())
        node.installAdminRpc(endpoint, connection, NodeAdminExtras())
        val shell = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        try {
            withTimeout(TEST_TIMEOUT) {
                endpoint.create()
                node.start(endpoint, connection)
                val carrier = IrohNodeCarrier(shell, scope, connectTimeout = DIAL_TIMEOUT)
                val address = NodeAddress("iroh://${endpoint.ticketString()}")

                val session = NodeSession.open(carrier, NodeOpenRequest(address, NodeCredential.Token(token)), scope)
                assertEquals(endpoint.nodeIdHex(), session.node.value)
                assertTrue(session.capabilities.supports(NodeCarrierWire.FRAME_PART_CAPABILITY), "${session.capabilities}")
                val health = session.adminRpc("health.check", null)
                assertEquals(true, (health.json[NodeCarrierWire.SUCCESS] as? JsonPrimitive)?.booleanOrNull, "${health.json}")
                session.close()
                assertEquals(NodeLinkClosure.NORMAL, session.closure.value)

                val refused = runCatching {
                    NodeSession.open(carrier, NodeOpenRequest(address, NodeCredential.Token("wrong-$token")), scope)
                }.exceptionOrNull()
                assertTrue((refused as? NodeCarrierException)?.failure is NodeCarrierFailure.AuthRejected, "$refused")
            }
        } finally {
            withTimeoutOrNull(TEARDOWN_TIMEOUT) {
                runCatching { shell.shutdown() }
                runCatching { shell.close() }
                endpoint.shutdown()
            }
        }
        Unit
    }

    private companion object {
        val TEST_TIMEOUT = 40.seconds
        val DIAL_TIMEOUT = 10.seconds
        val TEARDOWN_TIMEOUT = 10.seconds
    }
}
