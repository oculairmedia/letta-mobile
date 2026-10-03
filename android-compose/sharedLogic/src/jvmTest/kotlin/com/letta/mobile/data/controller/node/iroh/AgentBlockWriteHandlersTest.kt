package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bfooy.5: the agent-scoped block create/delete routes map onto the native
 * MemFS commands (which commit, moving HEAD) and never touch the store.
 */
class AgentBlockWriteHandlersTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun createAgentWritesANewSystemMemoryFileWithACommitMessage() = runTest {
        val client = RecordingClient()
        val router = router(client)

        val response = dispatch(router, "block.create_agent", "label" to "notes", "value" to "first draft")

        assertTrue(response.contains("\"success\":true"), response)
        val write = client.writes.single()
        assertEquals(LocalBackendFixtureStore.AGENT_ID, write.agentId)
        assertEquals("system/notes.md", write.path)
        assertEquals("first draft", write.content)
        assertEquals("block.create_agent: notes", write.commitMessage)
        val block = result(response)
        assertEquals("notes", block.getValue("label").jsonPrimitive.content)
        assertEquals(
            LocalBackendBlockReader.blockIdFor(LocalBackendFixtureStore.AGENT_ID, "notes"),
            block.getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun createAgentRefusesAnExistingLabelWithoutWriting() = runTest {
        val client = RecordingClient()
        val router = router(client)

        val response = dispatch(router, "block.create_agent", "label" to LocalBackendFixtureStore.BLOCK_LABEL)

        assertTrue(response.contains("\"success\":false"), response)
        assertTrue(response.contains("already exists"), response)
        assertTrue(client.writes.isEmpty(), "a colliding create must never clobber the existing block")
    }

    @Test
    fun createAgentDefaultsAMissingValueToAnEmptyBlock() = runTest {
        val client = RecordingClient()
        dispatch(router(client), "block.create_agent", "label" to "scratch")
        assertEquals("", client.writes.single().content)
    }

    @Test
    fun deleteAgentIssuesDeleteMemoryFileAndEchoesTheDeletedBlock() = runTest {
        val client = RecordingClient()
        val router = router(client)

        val response = dispatch(router, "block.delete_agent", "label" to LocalBackendFixtureStore.BLOCK_LABEL)

        assertTrue(response.contains("\"success\":true"), response)
        val delete = client.deletes.single()
        assertEquals("system/${LocalBackendFixtureStore.BLOCK_LABEL}.md", delete.path)
        assertEquals("block.delete_agent: ${LocalBackendFixtureStore.BLOCK_LABEL}", delete.commitMessage)
        val echoed = result(response)
        assertEquals(LocalBackendFixtureStore.blockId, echoed.getValue("id").jsonPrimitive.content)
        assertEquals("true", echoed.getValue("deleted").jsonPrimitive.content)
        assertEquals("true", echoed.getValue("committed").jsonPrimitive.content)
    }

    @Test
    fun deleteAgentSurfacesTheNativeFailure() = runTest {
        val client = RecordingClient(deleteError = "memfs not initialized")
        val response = dispatch(router(client), "block.delete_agent", "label" to "persona")
        assertTrue(response.contains("\"success\":false"), response)
        assertTrue(response.contains("memfs not initialized"), response)
    }

    @Test
    fun traversalLabelsAreRejectedBeforeAnyNativeCall() = runTest {
        val client = RecordingClient()
        val router = router(client)

        listOf("block.create_agent", "block.delete_agent").forEach { method ->
            val response = dispatch(router, method, "label" to "../escape")
            assertTrue(response.contains("\"success\":false"), "$method: $response")
        }
        assertTrue(client.writes.isEmpty() && client.deletes.isEmpty())
    }

    private fun router(client: AppServerClient): AdminRpcRouter {
        val root = Files.createTempDirectory("agent-block-writes").toFile().also { roots += it }
        LocalBackendFixtureStore.create(root)
        val store = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1")
        return AdminRpcRouter().also { ToolAdminHandlers.register(it, store, client) }
    }

    private suspend fun dispatch(router: AdminRpcRouter, method: String, vararg extra: Pair<String, String>): String =
        router.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = buildJsonObject {
                    put("agent_id", LocalBackendFixtureStore.AGENT_ID)
                    extra.forEach { (k, v) -> put(k, v) }
                },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    private fun result(response: String): JsonObject =
        Json.parseToJsonElement(response).jsonObject.getValue("result").jsonObject

    private class RecordingClient(private val deleteError: String? = null) : AppServerClient {
        val writes = mutableListOf<AppServerCommand.WriteMemoryFile>()
        val deletes = mutableListOf<AppServerCommand.DeleteMemoryFile>()

        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = error("unused")

        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
            error("unused")

        override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile) =
            AppServerInboundFrame.WriteMemoryFileResponse(command.requestId, true, command.agentId, command.path)
                .also { writes += command }

        override suspend fun deleteMemoryFile(command: AppServerCommand.DeleteMemoryFile) =
            AppServerInboundFrame.DeleteMemoryFileResponse(
                requestId = command.requestId,
                success = deleteError == null,
                agentId = command.agentId,
                path = command.path,
                committed = deleteError == null,
                error = deleteError,
            ).also { deletes += command }
    }
}
