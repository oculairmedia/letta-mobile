package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.github.ajalt.clikt.core.parse
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohConnectionAuthentication
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import computer.iroh.Connection
import computer.iroh.NoHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotebookAdminRoutesTest {
    @Test
    fun transportPrincipalCannotBeSelectedByRequestAndRevocationStopsSave() = runBlocking {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/notebook-authority/requests-v1.json"))
            .use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        val referenceMethod = fixture.getValue("reference").jsonObject.getValue("method").jsonPrimitive.content
        val openMethod = fixture.getValue("open").jsonObject.getValue("method").jsonPrimitive.content
        val saveMethod = fixture.getValue("save").jsonObject.getValue("method").jsonPrimitive.content
        val root = Files.createTempDirectory("notebook-host-route")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { store ->
            val storageId = store.create("Document")
            val id = requireNotNull(store.read(storageId)).id
            val router = AdminRpcRouter()
            registerNotebookAdminRoutes(router, store, FileNotebookGrantStore(root.resolve("grants.json")))
            val unauthenticated = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation(
                requestId = "unauthenticated", method = referenceMethod,
                params = buildJsonObject { put("document_id", id.value); put("principal", "a".repeat(64)) },
                context = AdminRpcRequestContext(authenticated = false, authenticatedEndpointId = "a".repeat(64)),
            ))).jsonObject
            assertEquals("false", unauthenticated.getValue("success").jsonPrimitive.content)
            val alice = connection(router, "a".repeat(64))
            val bob = connection(router, "b".repeat(64))
            val reference = { peer: String ->
                buildJsonObject { put("document_id", id.value); put("principal", peer); put("agent_id", peer) }
            }
            assertEquals("denied", alice.call(referenceMethod, reference("a".repeat(64))).status())
            val args = listOf("--file", root.resolve("grants.json").toString(), "--principal", "a".repeat(64), "--document", id.value)
            NotebookGrantCommand().parse(args + "--read")
            assertEquals("denied", alice.call(referenceMethod, reference("a".repeat(64))).status())
            NotebookGrantCommand().parse(args + listOf("--read", "--edit", "--reference"))
            assertEquals("denied", bob.call(referenceMethod, reference("a".repeat(64))).status())
            assertEquals("found", alice.call(referenceMethod, reference("b".repeat(64))).status())
            assertEquals("found", alice.call(openMethod, reference("b".repeat(64))).status())
            NotebookGrantCommand().parse(args + "--revoke")
            val saved = alice.call(saveMethod, buildJsonObject {
                put("document_id", id.value); put("expected", ""); put("replacement", "forged")
            })
            assertEquals("denied", saved.status())
            assertEquals("", store.read(storageId)?.markdown)
            Files.writeString(root.resolve("grants.json"), "{broken")
            assertEquals("unavailable", alice.call(referenceMethod, reference("a".repeat(64))).status())
        }
    }

    @Test
    fun installingGrantRoutesRejectsPreviouslyExposedSyncBeforeRegisteringAnyHandler() {
        val root = Files.createTempDirectory("notebook-route-isolation")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
            notebooks.repoForSync()
            val router = AdminRpcRouter()
            val failure = assertThrows(IllegalStateException::class.java) {
                registerNotebookAdminRoutes(router, notebooks, FileNotebookGrantStore(root.resolve("grants.json")))
            }
            assertTrue(failure.message.orEmpty().contains("already exposed"))
            assertEquals(0, router.methodCount)
        }
    }

    private fun connection(router: AdminRpcRouter, peer: String): IrohNodeConnection {
        val peers = InMemoryPairedPeerStore()
        peers.save(PairedPeer(nodeId = peer, name = peer, pairedAtMs = 0L, capabilities = emptySet()))
        val controller = AppServerController::class.java.cast(Proxy.newProxyInstance(
            AppServerController::class.java.classLoader, arrayOf(AppServerController::class.java),
        ) { _, method, _ -> error("Unexpected controller call ${method.name}") })
        return IrohNodeConnection(
            connection = Connection(NoHandle), controller = controller, adminRpcRouter = router,
            authentication = IrohConnectionAuthentication(
                authPolicy = IrohAuthPolicy.PairedPeersOnly,
                pairingService = IrohPairingService(peers), remoteEndpointId = peer,
            ),
        )
    }

    private suspend fun IrohNodeConnection.call(method: String, params: JsonObject): JsonObject {
        val frame = buildJsonObject { put("request_id", "test"); put("method", method); put("params", params) }
        val raw = suspendCoroutine<String> { continuation ->
            val handler = IrohNodeConnection::class.java.getDeclaredMethod(
                "handleControlAdminRpc", JsonObject::class.java, Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = handler.invoke(this, frame, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result as String)
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            }
        }
        val parsed = Json.parseToJsonElement(raw).jsonObject
        assertTrue(parsed["success"]?.jsonPrimitive?.content == "true", raw)
        return requireNotNull(parsed["result"]).jsonObject
    }

    private fun JsonObject.status() = requireNotNull(this["status"]).jsonPrimitive.content
}
