package com.letta.mobile.data.workspace.relay

import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsException
import com.letta.mobile.data.memory.memfs.MemfsFileRef
import com.letta.mobile.data.secrets.AgentSecretsException
import com.letta.mobile.data.secrets.AppServerAgentSecretsSource
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.data.transport.appserver.WorkspaceRelayAccess
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.data.workspace.AppServerWorkspaceFileSource
import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.WorkspaceFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * letta-mobile-bzvro.37: the workspace sources work unchanged over an Iroh host's relay, and say
 * why when the host cannot relay.
 */
class WorkspaceRelayClientTest {
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    private fun relayAnswering(vararg frames: String): WorkspaceRelayCall = WorkspaceRelayCall { method, params ->
        calls += method to params
        AppServerInboundFrame.AdminRpcResponse(
            requestId = "admin-1",
            success = true,
            result = Json.parseToJsonElement("""{"frames":[${frames.joinToString(",")}]}"""),
        )
    }

    private fun relayFailing(error: String): WorkspaceRelayCall = WorkspaceRelayCall { method, params ->
        calls += method to params
        AppServerInboundFrame.AdminRpcResponse(requestId = "admin-1", success = false, error = error)
    }

    private fun memfs(relay: WorkspaceRelayCall) =
        AppServerMemfsSource(client = { WorkspaceRelayClient(relay) }, events = emptyFlow(), requestId = { "client-$it" })

    @Test
    fun theMemoryBrowserListsEveryPageThroughTheRelay() = runTest {
        val listing = memfs(
            relayAnswering(
                """{"type":"list_memory_response","request_id":"h","entries":[{"relative_path":"system/human/communication_style.md","is_system":true,"content":"x","size":1}],"done":false,"success":true}""",
                """{"type":"list_memory_response","request_id":"h","entries":[{"relative_path":"notes.md","content":"y","size":1}],"done":true,"success":true}""",
            ),
        ).list("agent-1")

        assertEquals(listOf("system/human/communication_style.md", "notes.md"), listing.files.map { it.path })
        assertEquals("memfs.list", calls.single().first)
        assertEquals("""{"agent_id":"agent-1"}""", calls.single().second.toString())
    }

    @Test
    fun aSaveRidesTheRelayToo() = runTest {
        val sha = memfs(
            relayAnswering("""{"type":"write_memory_file_response","request_id":"h","success":true,"committed":true,"commit_sha":"abc"}"""),
        ).write(MemfsFileRef("agent-1", "notes.md"), "hello")

        assertEquals("abc", sha)
        assertEquals("memfs.write", calls.single().first)
    }

    @Test
    fun anOutdatedHostSaysItNeedsAnUpdate() = runTest {
        val error = assertFailsWith<MemfsException> { memfs(relayFailing(AdminRpcErrors.unknownMethod("memfs.list"))).list("agent-1") }
        assertEquals(WorkspaceRelayClient.HOST_NEEDS_UPDATE, error.message)
    }

    @Test
    fun aDeviceWithoutTheCapabilityIsToldSo() = runTest {
        val secrets = AppServerAgentSecretsSource(client = { WorkspaceRelayClient(relayFailing("forbidden")) }, requestId = { it })
        val error = assertFailsWith<AgentSecretsException> { secrets.list("agent-1") }
        assertEquals(WorkspaceRelayClient.forbidden(WorkspaceRelayAccess.Secrets), error.message)
    }

    @Test
    fun secretValuesNeverReachAFailureMessage() = runTest {
        val secrets = AppServerAgentSecretsSource(
            client = { WorkspaceRelayClient(relayAnswering("""{"type":"secret_list_response","secrets":[{"key":"K","value":{"bad":"S3CRET"}}],"success":true}""")) },
            requestId = { it },
        )
        val error = assertFailsWith<AgentSecretsException> { secrets.list("agent-1") }
        assertFalse(error.toString().contains("S3CRET"))
        assertFalse(error.cause?.toString().orEmpty().contains("S3CRET"))
    }

    @Test
    fun workspaceFilesAreReadThroughTheRelay() = runTest {
        val files = AppServerWorkspaceFileSource(
            client = { WorkspaceRelayClient(relayAnswering("""{"type":"read_file_response","request_id":"h","path":"/repo/README.md","content":"# hi","success":true}""")) },
            requestId = { it },
        )
        val content = files.read("/repo/README.md")
        assertEquals("workspace.read_file", calls.single().first)
        assertEquals("# hi", (content as WorkspaceFileContent.Text).text)
    }

    @Test
    fun theRouteSendsDirectFirstThenTheRelayThenExplainsItHasNeither() = runTest {
        val direct = DirectClient()
        assertSame(direct, WorkspaceClientRoute(direct = { direct }, relay = { relayAnswering() }).client())
        assertEquals(WorkspaceRelayClient::class, WorkspaceClientRoute(direct = { null }, relay = { relayAnswering() }).client()::class)
        val none = assertFailsWith<IllegalStateException> { WorkspaceClientRoute().client() }
        assertEquals(WorkspaceClientRoute.NO_CONNECTION, none.message)

        val files = AppServerWorkspaceFileSource(client = WorkspaceClientRoute()::client, requestId = { it })
        assertEquals(WorkspaceClientRoute.NO_CONNECTION, assertFailsWith<WorkspaceFileException> { files.read("/x") }.message)
    }

    private class DirectClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun workspaceRequest(command: AppServerWorkspaceCommand): List<JsonObject> =
            listOf(Json.parseToJsonElement("{}").jsonObject)
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unused()
        override suspend fun input(command: AppServerCommand.Input): Unit = unused()
        override suspend fun sync(command: AppServerCommand.Sync) = unused()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unused()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unused()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unused()
        private fun unused(): Nothing = error("unused")
    }
}
