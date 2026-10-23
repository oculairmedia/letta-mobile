package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import computer.iroh.Connection
import computer.iroh.NoHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression coverage for letta-mobile-wisqe.2 node-connection handler boundaries.
class IrohNodeConnectionRuntimeStartModeCapabilityTest {
    @Test
    fun chatPeerCannotSetRuntimePermissionModeOrRegisterViewer() = runBlocking {
        val harness = RuntimeStartHarness.open(IrohPeerCapabilities.CHAT_ROLE - IrohPeerCapabilities.CONVERSATION_MANAGE)

        val response = harness.runtimeStart(mode = "unrestricted")

        assertEquals("error", response.string("type"))
        assertEquals("forbidden", response.string("message"))
        assertEquals(IrohPeerCapabilities.CONVERSATION_MANAGE, response.string("capability"))
        assertTrue(harness.controller.starts.isEmpty(), "denial must precede startRuntime")
        assertTrue(harness.viewersForConversation().isEmpty(), "denial must precede viewer registration")
    }

    @Test
    fun chatPeerCanStartRuntimeWithoutPermissionMode() = runBlocking {
        val harness = RuntimeStartHarness.open(IrohPeerCapabilities.CHAT_ROLE - IrohPeerCapabilities.CONVERSATION_MANAGE)

        val response = harness.runtimeStart(mode = null)

        assertEquals("runtime_start_response", response.string("type"))
        assertEquals("true", response.string("success"))
        assertEquals(listOf(StartCall(mode = null)), harness.controller.starts)
        assertEquals(1, harness.viewersForConversation().size)
    }

    @Test
    fun conversationManagerCanSetRuntimePermissionMode() = runBlocking {
        val harness = RuntimeStartHarness.open(IrohPeerCapabilities.CHAT_ROLE)

        val response = harness.runtimeStart(mode = "unrestricted")

        assertEquals("runtime_start_response", response.string("type"))
        assertEquals("true", response.string("success"))
        assertEquals(
            listOf(StartCall(mode = AppServerPermissionMode.Unrestricted)),
            harness.controller.starts,
        )
        assertEquals(1, harness.viewersForConversation().size)
    }

    @Test
    fun controlAdminRpcAgentScopeDenialDoesNotRegisterTheRequestedConversation() = runBlocking {
        val harness = RuntimeStartHarness.open(
            capabilities = IrohPeerCapabilities.CHAT_ROLE,
            router = AdminRpcRouter().apply {
                register("message.list") { JsonPrimitive("[]") }
            },
            agentScope = setOf("agent-A"),
        )
        harness.subscribeViewer("convA")

        val response = harness.messageList(conversationId = "convB", agentId = "agent-B")

        assertEquals("false", response.string("success"))
        assertEquals(AgentScopeGuard.DENIED, response.string("error"))
        assertEquals("convA", harness.currentViewerConversation)
        assertEquals(1, harness.viewersForConversation("convA").size)
        assertTrue(harness.viewersForConversation("convB").isEmpty(), "denied conversation must not gain a viewer")
    }

    @Test
    fun controlAdminRpcFailedMessageListRestoresThePreviousViewerSubscription() = runBlocking {
        val harness = RuntimeStartHarness.open(
            capabilities = IrohPeerCapabilities.CHAT_ROLE,
            router = AdminRpcRouter().apply {
                register("message.list") { throw IllegalStateException("native message list failed") }
            },
        )
        harness.subscribeViewer("convA")

        val response = harness.messageList(conversationId = "convB")

        assertEquals("false", response.string("success"))
        assertEquals("native message list failed", response.string("error"))
        assertEquals("convA", harness.currentViewerConversation)
        assertEquals(1, harness.viewersForConversation("convA").size)
        assertTrue(harness.viewersForConversation("convB").isEmpty(), "failed hydration must roll back its viewer")
    }

    @Test
    fun controlAdminRpcSuccessfulMessageListRegistersTheViewer() = runBlocking {
        val harness = RuntimeStartHarness.open(
            capabilities = IrohPeerCapabilities.CHAT_ROLE,
            router = AdminRpcRouter().apply {
                register("message.list") { JsonPrimitive("[]") }
            },
            agentScope = setOf("agent-A"),
        )

        val response = harness.messageList(conversationId = "convB", agentId = "agent-A")

        assertEquals("true", response.string("success"))
        assertEquals("convB", harness.currentViewerConversation)
        assertEquals(1, harness.viewersForConversation("convB").size)
    }

    private class RuntimeStartHarness private constructor(
        val controller: RecordingController,
        private val registry: ConnectionRegistry,
        private val connection: IrohNodeConnection,
        private val subscription: ConversationViewerSubscription,
    ) {

        suspend fun runtimeStart(mode: String?): JsonObject =
            AppServerProtocol.json.parseToJsonElement(connection.invokeRuntimeStart(
                buildJsonObject {
                    put("agent_id", AGENT_ID)
                    put("conversation_id", CONVERSATION_ID)
                    mode?.let { put("mode", it) }
                },
            )).jsonObject

        suspend fun messageList(conversationId: String, agentId: String? = null): JsonObject =
            AppServerProtocol.json.parseToJsonElement(
                connection.invokeControlAdminRpc(
                    buildJsonObject {
                        put("request_id", "message-list")
                        put("method", "message.list")
                        put(
                            "params",
                            buildJsonObject {
                                put("conversation_id", conversationId)
                                agentId?.let { put("agent_id", it) }
                            },
                        )
                    },
                ),
            ).jsonObject

        suspend fun subscribeViewer(conversationId: String) = subscription.subscribe(conversationId)

        val currentViewerConversation: String?
            get() = subscription.currentConversation

        suspend fun viewersForConversation(conversationId: String = CONVERSATION_ID) = registry.viewersFor(conversationId)

        companion object {
            private const val AGENT_ID = "agent-1"
            private const val CONVERSATION_ID = "conversation-1"
            private const val REMOTE_ENDPOINT_ID = "runtime-start-test-peer"

            suspend fun open(
                capabilities: Set<String>,
                router: AdminRpcRouter = AdminRpcRouter(),
                agentScope: Set<String>? = null,
            ): RuntimeStartHarness {
                val controller = RecordingController()
                val registry = ConnectionRegistry()
                val connection = IrohNodeConnection(
                    connection = Connection(NoHandle),
                    controller = controller.fake,
                    adminRpcRouter = router,
                    authentication = authentication(capabilities, agentScope),
                    connectionRegistry = registry,
                )
                val viewer = object : ViewerHandle {
                    override val connectionId = REMOTE_ENDPOINT_ID

                    override suspend fun writeFrame(frame: String): Boolean = true
                }
                val registration = registry.claim(viewer)
                val subscription = ConversationViewerSubscription(registry, registration)
                IrohNodeConnection::class.java.getDeclaredField("viewerSubscription").also { field ->
                    field.isAccessible = true
                    field.set(connection, subscription)
                }
                return RuntimeStartHarness(controller, registry, connection, subscription)
            }

            private fun authentication(
                capabilities: Set<String>,
                agentScope: Set<String>? = null,
            ): IrohConnectionAuthentication {
                val peerStore = InMemoryPairedPeerStore()
                peerStore.save(
                    PairedPeer(
                        nodeId = REMOTE_ENDPOINT_ID,
                        name = "runtime-start-test-peer",
                        pairedAtMs = 0L,
                        capabilities = capabilities,
                        agentIds = agentScope,
                    ),
                )
                return IrohConnectionAuthentication(
                    authPolicy = IrohAuthPolicy.PairedPeersOnly,
                    pairingService = IrohPairingService(peerStore),
                    remoteEndpointId = REMOTE_ENDPOINT_ID,
                )
            }
        }
    }

    private class RecordingController {
        val starts = mutableListOf<StartCall>()
        val fake: AppServerController = AppServerController::class.java.cast(
            Proxy.newProxyInstance(
                AppServerController::class.java.classLoader,
                arrayOf(AppServerController::class.java),
            ) { _, method, arguments -> handle(method.name, arguments) },
        )

        private fun handle(method: String, arguments: Array<Any?>?): Any? = when {
            method.startsWith("startRuntime") -> {
                val values = requireNotNull(arguments)
                val agentId = requireNotNull(values[0] as? String)
                val conversationId = requireNotNull(values[1] as? String)
                starts += StartCall(mode = values[3] as? AppServerPermissionMode)
                CanonicalRuntime(AppServerRuntimeScope(agentId, conversationId))
            }

            method == "toString" -> "RuntimeStartRecordingController"
            method == "hashCode" -> System.identityHashCode(this)
            method == "equals" -> this === arguments?.firstOrNull()
            else -> error("Unexpected AppServerController call: $method")
        }
    }

    private data class StartCall(val mode: AppServerPermissionMode?)
}

private suspend fun IrohNodeConnection.invokeRuntimeStart(frame: JsonObject): String =
    suspendCoroutine { continuation ->
        val method = IrohNodeConnection::class.java.getDeclaredMethod(
            "handleRuntimeStart",
            JsonObject::class.java,
            String::class.java,
            Continuation::class.java,
        )
        method.isAccessible = true
        try {
            val response = method.invoke(this, frame, "runtime-start", continuation)
            if (response !== COROUTINE_SUSPENDED) {
                continuation.resume(requireNotNull(response as? String))
            }
        } catch (failure: InvocationTargetException) {
            continuation.resumeWithException(failure.targetException)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
        }
    }

private suspend fun IrohNodeConnection.invokeControlAdminRpc(frame: JsonObject): String =
    suspendCoroutine { continuation ->
        val method = IrohNodeConnection::class.java.getDeclaredMethod(
            "handleControlAdminRpc",
            JsonObject::class.java,
            Continuation::class.java,
        )
        method.isAccessible = true
        try {
            val response = method.invoke(this, frame, continuation)
            if (response !== COROUTINE_SUSPENDED) {
                continuation.resume(requireNotNull(response as? String))
            }
        } catch (failure: InvocationTargetException) {
            continuation.resumeWithException(failure.targetException)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
        }
    }

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
