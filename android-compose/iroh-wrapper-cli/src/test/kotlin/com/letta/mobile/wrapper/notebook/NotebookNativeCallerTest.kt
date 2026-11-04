package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.parse
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.controller.node.iroh.IrohEndpointBinding
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Uses the production endpoint and both RPC transports, with ephemeral native test identities. */
@EnabledIfEnvironmentVariable(named = "RUN_NOTEBOOK_NATIVE_TESTS", matches = "true")
class NotebookNativeCallerTest {
    @Test
    @Timeout(90)
    fun authenticatedEndpointOwnsGrantDespitePayloadClaimsAndActiveConnectionRevocation() = runBlocking {
        val root = Files.createTempDirectory("notebook-native-caller")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val bob = Endpoint.bind(EndpointOptions(relayMode = RelayMode.disabled()))
        val peers = InMemoryPairedPeerStore()
        listOf(endpointId(alice), endpointId(bob)).forEach { peer ->
            peers.save(PairedPeer(nodeId = peer, name = peer, pairedAtMs = 0L, capabilities = emptySet()))
        }
        val host = IrohNodeEndpoint(
            scope = scope, authPolicy = IrohAuthPolicy.PairedPeersOnly,
            binding = IrohEndpointBinding(relayConfig = IrohRelayConfig.Disabled),
            pairingService = IrohPairingService(peers),
        )
        try {
            NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
                registerNotebookAdminRoutes(host.adminRpcRouter, notebooks, FileNotebookGrantStore(root.resolve("grants.json")))
                // Endpoint.create waits for relay online(), which cannot complete in disabled-relay mode.
                // Inject only the real native binding; start/accept/authentication/RPC are production paths.
                val nativeHost = Endpoint.bind(EndpointOptions(
                    relayMode = RelayMode.disabled(), alpns = listOf(IrohNodeEndpoint.DEFAULT_ALPN),
                ))
                IrohNodeEndpoint::class.java.getDeclaredField("endpoint").apply { isAccessible = true }.set(host, nativeHost)
                host.start(DefaultAppServerController(UnusedClient))
                withTimeout(30_000) { assertAuthorities(root, notebooks, host.addr(), listOf(alice, bob)) }
            }
        } finally {
            host.shutdown()
            scope.cancel()
            alice.shutdown()
            bob.shutdown()
            alice.close()
            bob.close()
        }
    }

    private suspend fun assertAuthorities(
        root: Path,
        notebooks: NotebookLocalStore,
        host: computer.iroh.EndpointAddr,
        peers: List<Endpoint>,
    ) {
        val (alice, bob) = peers
        val aliceId = endpointId(alice)
        val bobId = endpointId(bob)
        val storageId = notebooks.create("Secret")
        val otherId = requireNotNull(notebooks.read(notebooks.create("Other"))).id.value
        val id = requireNotNull(notebooks.read(storageId)).id.value
        alice.connect(host, IrohNodeEndpoint.DEFAULT_ALPN).use { aliceConnection ->
            bob.connect(host, IrohNodeEndpoint.DEFAULT_ALPN).use { bobConnection ->
                val aliceControl = authenticate(aliceConnection)
                val bobControl = authenticate(bobConnection)
                val args = listOf("--file", root.resolve("grants.json").toString(),
                    "--principal", aliceId, "--document", id)
                assertEquals("denied", call(aliceControl, "notebook.reference.resolve", id, bobId).status())
                NotebookGrantCommand().parse(args + listOf("--read", "--reference"))
                assertEquals("denied", call(bobControl, "notebook.reference.resolve", id, aliceId).status())
                assertEquals("found", call(aliceControl, "notebook.reference.resolve", id, bobId).status())
                assertEquals("denied", call(aliceControl, "notebook.reference.resolve", otherId, aliceId).status())
                assertEquals("denied", call(aliceControl, "notebook.note.save", id, aliceId).status())
                assertEquals("", notebooks.read(storageId)?.markdown)
                NotebookGrantCommand().parse(args + listOf("--read", "--edit", "--reference"))
                assertEquals("found", call(aliceControl, "notebook.note.open", id, bobId).status())
                assertEquals("saved", streamCall(aliceConnection, "notebook.note.save", id, bobId).status())
                assertEquals("native edit", notebooks.read(storageId)?.markdown)
                NotebookGrantCommand().parse(args + "--revoke")
                assertEquals("denied", streamCall(aliceConnection, "notebook.note.save", id, aliceId).status())
                assertEquals("denied", call(aliceControl, "notebook.reference.resolve", id, aliceId).status())
                assertEquals("native edit", notebooks.read(storageId)?.markdown)
                Files.writeString(root.resolve("grants.json"), "{corrupt")
                assertEquals("unavailable", streamCall(aliceConnection, "notebook.reference.resolve", id, aliceId).status())
            }
        }
    }

    private suspend fun authenticate(connection: Connection): BiStream {
        val control = connection.openBi()
        IrohFrameCodec.write(control.send(), """{"type":"auth","request_id":"auth"}""")
        connection.openBi().send().writeAll(byteArrayOf(0))
        val auth = Json.parseToJsonElement(requireNotNull(IrohFrameCodec.readOne(control.recv()))).jsonObject
        assertEquals("true", auth.getValue("success").jsonPrimitive.content, auth.toString())
        return control
    }

    private suspend fun streamCall(connection: Connection, method: String, id: String, claim: String): JsonObject =
        connection.openBi().use { call(it, method, id, claim) }

    private suspend fun call(stream: BiStream, method: String, id: String, claim: String): JsonObject {
        val frame = buildJsonObject {
            put("type", "admin_rpc"); put("request_id", "call"); put("method", method)
            put("params", buildJsonObject {
                put("document_id", id); put("principal", claim); put("agent_id", claim)
                put("expected", ""); put("replacement", "native edit")
            })
        }
        IrohFrameCodec.write(stream.send(), frame.toString())
        val response = Json.parseToJsonElement(requireNotNull(IrohFrameCodec.readOne(stream.recv()))).jsonObject
        assertTrue(response.getValue("success").jsonPrimitive.content == "true", response.toString())
        return response.getValue("result").jsonObject
    }

    private fun endpointId(endpoint: Endpoint): String = endpoint.addr().id().toBytes()
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun JsonObject.status() = getValue("status").jsonPrimitive.content

    private object UnusedClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
