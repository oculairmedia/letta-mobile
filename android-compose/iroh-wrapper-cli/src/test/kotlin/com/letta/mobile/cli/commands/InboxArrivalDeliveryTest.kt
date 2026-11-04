package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.iroh.*
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.messaging.AgentMessageClientId
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.appserver.*
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import com.letta.mobile.data.transport.iroh.IrohAgentIdentity
import computer.iroh.Connection
import computer.iroh.NoHandle
import computer.iroh.SendStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Real receiver -> wrapper -> persisted transcript -> endpoint -> authenticated home viewer -> decoder.
 * Only the App Server boundary and the JNI byte sink are substituted. The boundary refuses missing
 * agents/conversations, wrong runtime, invalid input kind/role and missing request/client identities.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxArrivalDeliveryTest {
    @ParameterizedTest
    @CsvSource("true,false", "false,false", "true,true")
    fun `queued acceptance waits for committed user identity then reaches only authorized opted in home viewers`(existingInbox: Boolean, ownedDefault: Boolean) = runTest {
        val root = Files.createTempDirectory("arrival-delivery").toFile()
        try {
            val backend = if (ownedDefault) effectiveA2aBackendDir(null, true, root.path) else File(root, "backend")
            assertNotNull(backend, "owned App Server default must build the arrival observer")
            backend!!
            if (ownedDefault) assertEquals(File(root, ".letta/lc-local-backend"), backend)
            File(backend, "agents").mkdirs()
            File(backend, "agents/recipient.json").writeText("""{"id":"recipient"}""")
            val inbox = conversationDir(backend, "inbox")
            if (existingInbox) conversation(backend, "inbox", "lane:inbox")
            val home = conversation(backend, "home", "lane:home")
            val registry = ConnectionRegistry()
            val endpoint = IrohNodeEndpoint(scope = backgroundScope, authPolicy = IrohAuthPolicy.PairedPeersOnly,
                connectionRegistry = registry)
            val capable = homeViewer("capable", registry, advertised = true)
            val legacy = homeViewer("legacy", registry, advertised = false)
            val scoped = homeViewer("scoped", registry, advertised = true, peer = PairedPeer(
                "scoped", "scoped", 0, IrohPeerCapabilities.CHAT_ROLE - IrohPeerCapabilities.CONVERSATION_MANAGE, null,
            ))
            val otherAgent = homeViewer("other-agent", registry, advertised = true, peer = PairedPeer(
                "other-agent", "other-agent", 0, IrohPeerCapabilities.CHAT_ROLE, setOf("someone-else"),
            ))
            val revoked = homeViewer("revoked", registry, advertised = true)
            revoked.connection.revokeSession()
            val client = TranscriptClient(backend)
            val sender = IrohAgentIdentity.loadOrCreate("sender", File(root, "identities"))
            val wiring = buildA2aWiring(A2aWiringConfig(File(root, "identities"), File(root, "addresses.kv")),
                { client }, backend, scope = backgroundScope, activityTarget = endpoint.conversationActivityTarget())
            val message = IrohAgentMessage("sender", "recipient", "wake", "stable-wake", 1)

            val outcome = withContext(Dispatchers.IO) { wiring.receiver.respond(message, sender.nodeIdHex) }
            assertEquals("application_input_accepted", outcome.reason)
            runCurrent()
            assertTrue(capable.frames.isEmpty(), "acceptance alone must never create a persisted arrival")
            val input = client.inputs.single()
            val clientId = (input.payload as AppServerInputPayload.CreateMessage).messages.single().clientMessageId!!
            assertEquals("stable-wake", AgentMessageClientId.decode(clientId)?.msgId)
            // Wrong role and a content twin with a different identity are insufficient.
            File(inbox, "messages.jsonl").writeText(row("assistant", "assistant", clientId) + row("twin", "user", "other"))
            withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1_100) }
            assertTrue(capable.frames.isEmpty())
            File(inbox, "messages.jsonl").appendText(row("committed-source", "user", clientId))
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(10_000) { while (capable.frames.isEmpty()) { yield(); kotlinx.coroutines.delay(10) } }
            }
            assertEquals(1, capable.frames.size, "a committed inbox arrival must reach the opted-in home observer")

            val decoded = Json.decodeFromString(ServerFrameSerializer, capable.frames.single())
            val activity = assertInstanceOf(ServerFrame.ConversationActivity::class.java, decoded)
            assertEquals("persisted", activity.milestone)
            assertEquals("committed-source", activity.sourceMessageId)
            assertEquals(clientId, activity.clientMessageId)
            assertEquals("recipient", activity.agentId)
            assertEquals("inbox", activity.conversationId)
            assertNull(WsFrameMapper.toLettaMessage(activity), "activity must not append to the home timeline")
            assertTrue(legacy.frames.isEmpty(), "old clients receive neither activity nor conversation invalidation")
            assertTrue(scoped.frames.isEmpty())
            assertTrue(otherAgent.frames.isEmpty())
            assertTrue(revoked.frames.isEmpty())
            assertEquals(5, registry.viewersFor("home").size)
            assertTrue(registry.viewersFor("inbox").isEmpty(), "arrival observation must never subscribe a home viewer to inbox")
            assertFalse(File(home, "messages.jsonl").exists())
            assertEquals("duplicate_msg_id", wiring.receiver.respond(message, sender.nodeIdHex).reason)
            assertEquals(1, client.inputs.size)
            assertEquals(if (existingInbox) 0 else 1, client.creates)
        } finally { root.deleteRecursively() }
    }

    private fun conversation(root: File, id: String, lane: String): File {
        return conversationDir(root, id).apply {
            mkdirs()
            File(this, "conversation.json").writeText("""{"id":"$id","agent_id":"recipient","tags":["$lane"]}""")
        }
    }

    private fun conversationDir(root: File, id: String): File {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
        return File(root, "conversations/$key")
    }

    private fun row(id: String, role: String, otid: String): String = buildJsonObject {
        put("id", id); put("role", role); put("otid", otid)
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "wake") }) })
    }.toString() + "\n"

    private data class HomeViewer(val connection: IrohNodeConnection, val frames: MutableList<String>)

    private suspend fun homeViewer(
        id: String,
        registry: ConnectionRegistry,
        advertised: Boolean,
        peer: PairedPeer = PairedPeer(id, id, 0, IrohPeerCapabilities.CHAT_ROLE, null),
    ): HomeViewer {
        val peers = InMemoryPairedPeerStore().apply { save(peer) }
        val unusedController = Proxy.newProxyInstance(AppServerController::class.java.classLoader,
            arrayOf(AppServerController::class.java)) { _, method, _ -> error("unexpected controller call: ${method.name}") } as AppServerController
        val node = IrohNodeConnection(Connection(NoHandle), unusedController,
            authentication = IrohConnectionAuthentication(IrohAuthPolicy.PairedPeersOnly,
                pairingService = IrohPairingService(peers), remoteEndpointId = id), connectionRegistry = registry)
        val auth = IrohNodeConnection::class.java.getDeclaredMethod("handleAuth", JsonObject::class.java, String::class.java)
            .apply { isAccessible = true }
        val response = auth.invoke(node, buildJsonObject {
            put("capabilities", buildJsonArray { if (advertised) add(ServerFrame.ConversationActivity.CAPABILITY) })
        }, "auth-1") as String
        assertTrue(Json.parseToJsonElement(response).jsonObject["success"]!!.jsonPrimitive.boolean)
        val viewer = IrohNodeConnection::class.java.getDeclaredMethod("ensureSelfViewer", SendStream::class.java)
            .apply { isAccessible = true }.invoke(node, SendStream(NoHandle)) as ViewerHandle
        val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
        // Exercise the production viewer's gates and length framing with an in-memory JNI sink.
        viewer.javaClass.getDeclaredField("sink").apply { isAccessible = true }.set(viewer, object : ViewerFrameSink {
            override suspend fun writeAll(bytes: ByteArray) {
                val buffer = ByteBuffer.wrap(bytes)
                val length = buffer.int
                assertEquals(length, buffer.remaining())
                frames += ByteArray(length).also { buffer.get(it) }.toString(Charsets.UTF_8)
            }
        })
        registry.register("home", viewer)
        return HomeViewer(node, frames)
    }

    private inner class TranscriptClient(private val backend: File) : AppServerClient {
        private val loadedAgents = setOf("recipient").also { require(File(backend, "agents/recipient.json").isFile) }
        var creates = 0
        val inputs = mutableListOf<AppServerCommand.Input>()
        override val events = emptyFlow<AppServerReceivedFrame>()
        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
            require(command.runtime.agentId in loadedAgents)
            require(File(backend, "agents/${command.runtime.agentId}.json").isFile)
            require(command.runtime.agentId == "recipient" && command.runtime.conversationId == "inbox")
            require(File(conversationDir(backend, "inbox"), "conversation.json").isFile)
            require(!command.requestId.isNullOrBlank())
            val messages = (command.payload as AppServerInputPayload.CreateMessage).messages
            require(messages.size == 1 && messages.single().role == "user" && !messages.single().clientMessageId.isNullOrBlank())
            inputs += command
            return AppServerInboundFrame.InputAccepted(command.requestId!!, command.runtime, true, disposition = "queued")
        }
        override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse {
            require(command.requestId.isNotBlank())
            val query = requireNotNull(command.query)
            require(query["agent_id"]?.jsonPrimitive?.content == "recipient")
            require(query["limit"]?.jsonPrimitive?.int == 500)
            require(query["include_hidden"]?.jsonPrimitive?.boolean == true)
            val rows = File(backend, "conversations").listFiles().orEmpty().mapNotNull { directory ->
                File(directory, "conversation.json").takeIf { it.isFile }?.let {
                    Json.parseToJsonElement(it.readText()).jsonObject
                }
            }.filter { it["agent_id"]?.jsonPrimitive?.content == "recipient" }.sortedBy { it["id"]!!.jsonPrimitive.content }
            // This fixture only models the first, short page; refuse requests it cannot model faithfully.
            require(query["after"] == null && rows.size < 500)
            return AppServerInboundFrame.ConversationListResponse(command.requestId, true, JsonArray(rows))
        }
        override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate): AppServerInboundFrame.ConversationCreateResponse {
            require(command.body["agent_id"]?.jsonPrimitive?.content in loadedAgents)
            require(File(backend, "agents/recipient.json").isFile)
            require(command.body["tags"] == JsonArray(listOf(JsonPrimitive("lane:inbox"))))
            require(command.body["conversation_class"]?.jsonPrimitive?.content == "autonomous")
            creates++
            val directory = conversation(backend, "inbox", "lane:inbox")
            return AppServerInboundFrame.ConversationCreateResponse(command.requestId, true,
                Json.parseToJsonElement(File(directory, "conversation.json").readText()).jsonObject)
        }
        override suspend fun input(command: AppServerCommand.Input) = error("must await correlated acceptance")
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
