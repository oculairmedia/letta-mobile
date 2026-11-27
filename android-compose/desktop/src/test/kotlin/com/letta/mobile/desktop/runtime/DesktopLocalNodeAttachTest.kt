package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.node.embedded.BundledRuntimeNode
import com.letta.mobile.data.node.embedded.EmbeddedNodeHost
import com.letta.mobile.data.node.embedded.embeddedNodeConnectConfig
import com.letta.mobile.data.node.embedded.embeddedNodeTransport
import com.letta.mobile.data.node.host.AppServerProcessSource
import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The desktop with no remote host configured: the shell attaches to the desktop's own node (its real composition, here
 * over a temporary state directory and with no App Server) through a node session on the in-process carrier, with the
 * same channel transport it uses for a node over Iroh, and reads and writes the node's host-held state through it.
 */
class DesktopLocalNodeAttachTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state: File = Files.createTempDirectory("desktop-node").toFile()
    private val backend: File = File(state, "backend").apply { mkdirs() }
    private val bundled = BundledRuntimeNode(
        ownedProcess = AppServerProcessSource.Owned { null },
        backendDirectory = { backend },
        appServer = { null },
        logFile = File(state, "node.log"),
        stateDirectory = File(state, "node"),
    )
    private val node = EmbeddedNodeHost(bundled.spec(), scope)
    private val transport: IrohChannelTransport = embeddedNodeTransport(node, DEVICE_ID, scope)

    @AfterTest
    fun tearDown() = runBlocking {
        transport.close()
        node.close()
        scope.cancel()
        state.deleteRecursively()
        Unit
    }

    @Test
    fun theShellReadsAndWritesItsOwnNodesStateWithNoRemoteHost() = scenario {
        connect()
        assertIs<ChannelTransportState.Connected>(transport.state.value)
        val capabilities = listOf(
            AgentPinsWire.CAPABILITY,
            ShellStateWire.CAPABILITY,
            ConversationFeedWire.CAPABILITY,
            ApprovalRelay.CAPABILITY,
        )
        for (capability in capabilities) {
            assertTrue(capability in transport.hostCapabilities, "the node advertises $capability: ${transport.hostCapabilities}")
        }
        assertTrue(node.running.value, "the first dial started the node")
    }

    @Test
    fun pinsAreHostHeldAndPushedBack() = scenario {
        connect()
        val pushed = awaitPush(AgentPinsWire.FRAME_TYPE)
        val set = call(
            AgentPinsWire.SET,
            buildJsonObject {
                putJsonArray(AgentPinsWire.AGENT_IDS) { add(JsonPrimitive(AGENT)) }
                put(AgentPinsWire.EXPECTED_REVISION, 0)
            },
        )
        assertTrue(set.success, "pins.set: ${set.error}")
        assertTrue(AGENT in pushed.await().toString(), "the node pushes the change to its shell")
        val read = call(AgentPinsWire.GET, JsonObject(emptyMap()))
        val pinned = read.result?.jsonObject?.get(AgentPinsWire.AGENT_IDS) as JsonArray
        assertEquals(listOf(AGENT), pinned.map { (it as JsonPrimitive).content })
    }

    @Test
    fun shellStateIsHostHeldAndPushedBack() = scenario {
        connect()
        val pushed = awaitPush(ShellStateWire.FRAME_TYPE)
        val proposed = call(
            ShellStateWire.PROPOSE,
            buildJsonObject {
                put(
                    ShellStateWire.CHANGES,
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(ShellStateWire.KEY, THEME_KEY)
                                put(ShellStateWire.VALUE, "dark")
                                put(ShellStateWire.EXPECTED_REVISION, 0)
                            },
                        )
                    },
                )
            },
        )
        assertTrue(proposed.success, "shell.state.propose: ${proposed.error}")
        assertTrue(THEME_KEY in pushed.await().toString(), "the node pushes the change to its shell")
        assertTrue(THEME_KEY in call(ShellStateWire.GET, JsonObject(emptyMap())).result.toString())
    }

    @Test
    fun theFeedApprovalsAndSubagentRegistryAreServedByTheNode() = scenario {
        connect()
        val feed = call(
            ConversationFeedWire.SUBSCRIBE,
            buildJsonObject {
                put(ConversationFeedWire.CONVERSATION_ID, CONVERSATION)
                put(ConversationFeedWire.AGENT_ID, AGENT)
            },
        )
        assertTrue(feed.success, "feed.subscribe: ${feed.error}")
        assertEquals("snapshot", (feed.result?.jsonObject?.get("mode") as? JsonPrimitive)?.content)

        // No approval is parked, so the node refuses this one; what matters is that the node answered it.
        val approval = call("approval.submit", buildJsonObject { put("approval_id", "approval-none") })
        assertTrue(!approval.success && approval.error != null, "the node answers an unknown approval: $approval")

        val subagents = transport.sendSubagentListForScope(SubagentParentScope(AGENT, CONVERSATION), all = true, timeoutMs = 10_000)
        assertTrue(subagents.success, "subagent.list: ${subagents.error}")
        assertEquals(emptyList(), subagents.subagents)
    }

    @Test
    fun aRestartedNodeIsRedialledByTheShell() = scenario {
        connect()
        node.restart()
        // The shell's link was lost with the old generation; the transport redials and the next dial starts a new one.
        withTimeout(20.seconds) {
            while (!answers()) kotlinx.coroutines.delay(100)
        }
    }

    /** Whether the restarted node answers; a call caught by the old link's loss is part of the transition. */
    private suspend fun answers(): Boolean = try {
        node.running.value && call(AgentPinsWire.GET, JsonObject(emptyMap())).success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") lost: Exception) {
        false
    }

    private suspend fun connect() {
        val config = embeddedNodeConnectConfig(DEVICE_ID)
        transport.connect(config.baseShimUrl, config.token, config.deviceId, config.clientVersion)
    }

    private suspend fun call(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse =
        transport.adminRpc(method, "", params.toString())

    /** The next push of [type] the shell receives, subscribed before the call that causes it. */
    private fun CoroutineScope.awaitPush(type: String): Deferred<JsonObject> = async(start = CoroutineStart.UNDISPATCHED) {
        transport.events.filterIsInstance<ServerFrame.Unknown>().first { it.type == type }.raw
    }

    private fun scenario(body: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(60.seconds) { body() }
    }

    private companion object {
        const val DEVICE_ID = "letta-desktop"
        const val AGENT = "agent-local"
        const val CONVERSATION = "conv-local"
        const val THEME_KEY = "appearance.theme"
    }
}
