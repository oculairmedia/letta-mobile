package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.relay.AppToolCallRequest
import com.letta.mobile.data.controller.extras.relay.AppToolCallResult
import com.letta.mobile.data.controller.extras.relay.AppToolCallScope
import com.letta.mobile.data.controller.extras.relay.AppToolRelayAnswerer
import com.letta.mobile.data.controller.extras.relay.AppToolRelayHost
import com.letta.mobile.data.controller.extras.relay.AppToolRelayResponder
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.shell.layout.edit.ShellLayoutRelay
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/**
 * The app-tool relay over real loopback QUIC: an in-process [IrohNodeEndpoint] with the relay
 * attached, and an app dialing it the way desktop and Android do ([IrohAppServerTransportAdapter],
 * auth, runtime_start). A host call to `shell_layout` reaches the app's tool with the host's caller
 * and comes back; an app built without the relay is never sent one.
 *
 * OPT-IN like the other live-QUIC suites: `-DrunIrohLiveE2E=true`.
 */
class IrohAppToolRelayEndToEndTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val caller = ExternalToolCaller.verified(agentId = AGENT, conversationId = CONVERSATION, toolCallId = "tc-e2e")
    private val input = buildJsonObject { put("action", "get") }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(
            "Live-QUIC Iroh E2E is opt-in; pass -DrunIrohLiveE2E=true to run it.",
            System.getProperty("runIrohLiveE2E") == "true",
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** Stands in for the app's shell_layout answerer and records what it was asked. */
    private class RecordingAnswerer : AppToolRelayAnswerer {
        val requests = mutableListOf<AppToolCallRequest>()

        override suspend fun answer(request: AppToolCallRequest): AppToolCallResult {
            requests += request
            return AppToolCallResult("""{"lanes":{}}""")
        }
    }

    @Test
    fun aHostCallReachesTheViewingAppsToolAndReturnsItsAnswer() = runBlocking {
        val relay = ShellLayoutRelay.host(timeout = 10.seconds)
        val ticket = startHost(relay)
        val answerer = RecordingAnswerer()
        connectApp(ticket, AppToolRelayResponder { answerer })

        val result = withTimeout(20.seconds) { ShellLayoutRelay.hostTools(relay).single().invoke(input, caller) }

        assertEquals(ExternalToolResult.Success("""{"lanes":{}}"""), result)
        val request = answerer.requests.single()
        assertEquals("shell_layout", request.toolName)
        assertEquals(input, request.input)
        assertEquals(AppToolCallScope(AGENT, CONVERSATION, "tc-e2e"), request.scope)
    }

    @Test
    fun anAppWithoutTheRelayIsNeverSentACall() = runBlocking {
        val relay = ShellLayoutRelay.host(timeout = 10.seconds)
        val ticket = startHost(relay)
        connectApp(ticket, responder = null)

        val result = withTimeout(20.seconds) { ShellLayoutRelay.hostTools(relay).single().invoke(input, caller) }

        assertTrue(assertIs<ExternalToolResult.Error>(result).error.startsWith("No app is showing this conversation"))
    }

    private suspend fun startHost(relay: AppToolRelayHost): String {
        val server = IrohNodeEndpoint(scope = scope, authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly)
        server.attachAppToolRelay(relay)
        server.create()
        // A host whose runtimes start and run nothing: the relay is what is under test.
        server.start(FakeAppServerController())
        return server.ticketString()
    }

    /** Dials, signs in and starts the conversation's runtime, which makes this app its viewer. */
    private suspend fun connectApp(ticket: String, responder: AppToolRelayResponder?) {
        val endpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val transport = IrohAppServerTransportAdapter(endpoint, appToolRelay = responder).createTransport(
            endpoint = AppServerEndpoint(scheme = "iroh", address = ticket),
            scope = scope,
        ) as IrohAppServerTransport
        withTimeout(20.seconds) { transport.awaitConnectionReady() }
        val client = DefaultAppServerClient(transport, parentScope = scope)
        val auth = client.auth(
            AppServerCommand.Auth(requestId = "auth-e2e", token = "", capabilities = listOf(IrohFrameCodec.FRAME_PART_CAPABILITY)),
        )
        assertTrue(auth.success, "auth: ${auth.error}")
        val started = client.runtimeStart(
            AppServerCommand.RuntimeStart(requestId = "start-e2e", agentId = AGENT, conversationId = CONVERSATION),
        )
        assertTrue(started.success, "runtime_start: ${started.error}")
    }

    private companion object {
        const val AGENT = "agent-relay-e2e"
        const val CONVERSATION = "conv-relay-e2e"
    }
}
