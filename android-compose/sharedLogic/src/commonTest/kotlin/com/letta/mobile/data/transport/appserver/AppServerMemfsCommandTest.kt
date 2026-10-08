package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** letta-mobile-bzvro.24: the MemFS commands' wire shape, replay safety and lane. */
class AppServerMemfsCommandTest {
    private fun wire(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject

    @Test
    fun commandsEncodeWithTheirUpstreamFieldNames() {
        assertEquals(
            """{"type":"list_memory","request_id":"r1","agent_id":"a1","include_references":true}""",
            AppServerProtocol.encodeCommand(AppServerMemfsCommand.ListMemory("r1", "a1", includeReferences = true)),
        )
        assertEquals(
            """{"type":"memory_history","request_id":"r2","agent_id":"a1","file_path":"system/persona.md","limit":20}""",
            AppServerProtocol.encodeCommand(AppServerMemfsCommand.MemoryHistory("r2", "a1", "system/persona.md", 20)),
        )
        assertEquals(
            setOf("type", "request_id", "agent_id", "file_path", "ref"),
            wire(AppServerMemfsCommand.MemoryFileAtRef("r3", "a1", "x.md", "abc")).keys,
        )
        assertEquals(
            setOf("type", "request_id", "agent_id", "sha"),
            wire(AppServerMemfsCommand.MemoryCommitDiff("r4", "a1", "abc")).keys,
        )
    }

    @Test
    fun readsReplaySafelyAndEnablingDoesNot() {
        assertEquals(AppServerCommandRetryClass.SafeRead, AppServerCommandRetryClass.of(AppServerMemfsCommand.ListMemory("r", "a")))
        assertEquals(AppServerCommandRetryClass.SafeRead, AppServerCommandRetryClass.of(AppServerMemfsCommand.ReadMemoryFile("r", "a", "p")))
        assertIs<AppServerCommandRetryClass.AmbiguousMutation>(AppServerCommandRetryClass.of(AppServerMemfsCommand.EnableMemfs("r", "a")))
    }

    @Test
    fun readsTakeTheAdminLaneAndWritesTheRuntimeLane() = runTest {
        val runtime = LaneClient()
        val admin = LaneClient()
        val client = DualLaneAppServerClient(runtime = runtime, admin = admin)

        client.workspaceRequest(AppServerMemfsCommand.ListMemory("r1", "a"))
        client.workspaceRequest(AppServerMemfsCommand.EnableMemfs("r2", "a"))

        assertEquals(listOf("list_memory_response"), admin.sent)
        assertEquals(listOf("enable_memfs_response"), runtime.sent)
    }

    private class LaneClient : AppServerClient {
        val sent = mutableListOf<String>()
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun workspaceRequest(command: AppServerWorkspaceCommand): List<JsonObject> {
            sent += command.responseType
            return emptyList()
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()
        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }
}
