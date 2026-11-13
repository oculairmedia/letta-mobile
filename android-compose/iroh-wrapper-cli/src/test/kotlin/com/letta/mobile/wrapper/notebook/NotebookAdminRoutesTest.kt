package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.canvas.NotebookLocalStore
import com.github.ajalt.clikt.core.parse
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.canvas.authority.SharedNotesWire
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import java.nio.file.Files
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
    fun successfulFlushReturnsTheStoredTitleInsteadOfTheCallerClaim() = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("notebook-flush-title")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { store ->
            val storageId = store.create("Stored title")
            val id = requireNotNull(store.read(storageId)).id
            val router = AdminRpcRouter()
            registerNotebookAdminRoutes(router, store, FileNotebookGrantStore(root.resolve("grants.json")))
            val principal = "a".repeat(64)
            NotebookGrantCommand().parse(listOf(
                "--file", root.resolve("grants.json").toString(), "--principal", principal,
                "--document", id.value, "--read", "--edit",
            ))
            val caller = connection(router, principal)
            val opened = caller.call("notebook.note.open", buildJsonObject { put("document_id", id.value) })
            assertEquals("found", opened.status())
            assertEquals("Stored title", opened.getValue("snapshot").jsonObject.getValue("title").jsonPrimitive.content)
            val flushed = caller.call("notebook.note.flush", buildJsonObject {
                put("document_id", id.value)
                put("snapshot", buildJsonObject {
                    put("document_id", id.value); put("title", "Caller title"); put("markdown", "")
                })
            })
            assertEquals("saved", flushed.status())
            assertEquals("Stored title", flushed.getValue("snapshot").jsonObject.getValue("title").jsonPrimitive.content)
            assertEquals("Stored title", store.read(storageId)?.title)
        }
    }

    @Test
    fun transportPrincipalCannotBeSelectedByRequestAndRevocationStopsSave() = runBlocking {
        assumeDurableGrantHost()
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

    /** The shared listing takes no field but a string `cursor`, and a cursor this host did not issue to the caller is refused. */
    @Test
    fun theSharedListingTakesOnlyAStringCursorAndRefusesOneItDidNotIssue() = runBlocking {
        val root = Files.createTempDirectory("notebook-shared-list-params")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { store ->
            val router = AdminRpcRouter()
            registerNotebookAdminRoutes(router, store, FileNotebookGrantStore(root.resolve("grants.json")))
            val alice = connection(router, "a".repeat(64))

            assertEquals("listed", alice.call(SharedNotesWire.LIST, buildJsonObject {}).status())
            val forged = alice.call(SharedNotesWire.LIST, buildJsonObject { put(SharedNotesWire.CURSOR, "forged") })
            assertEquals("unavailable", forged.status())
            assertEquals(SharedNotesWire.INVALID_CURSOR, forged.getValue("reason").jsonPrimitive.content)
            val malformed = listOf(buildJsonObject { put("principal", "b".repeat(64)) }, buildJsonObject { put(SharedNotesWire.CURSOR, 1) })
            for (params in malformed) {
                assertEquals("false", alice.adminFrame(SharedNotesWire.LIST, params).getValue("success").jsonPrimitive.content, "$params")
            }
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

    private fun connection(router: AdminRpcRouter, peer: String): IrohNodeConnection = notebookPeer(router, peer)

    private suspend fun IrohNodeConnection.call(method: String, params: JsonObject): JsonObject {
        val parsed = adminFrame(method, params)
        assertTrue(parsed["success"]?.jsonPrimitive?.content == "true", parsed.toString())
        return requireNotNull(parsed["result"]).jsonObject
    }

    private fun JsonObject.status() = requireNotNull(this["status"]).jsonPrimitive.content
}
