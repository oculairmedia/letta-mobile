package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.memory.memfs.MemfsException
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The desktop binds the workspace sources to its direct App Server session, else its Iroh host's relay (letta-mobile-bzvro.24, .37). */
class DesktopMemfsBindingTest {
    @Test
    fun `with neither a direct session nor an iroh host the memory browser explains why`() = runTest {
        val sources = DesktopWorkspaceSources(DesktopLocalAppServerClientRegistry(), irohTransport = { null })
        val error = assertFailsWith<MemfsException> { sources.memfs().list("agent-1") }
        assertEquals(DesktopWorkspaceSources.NO_DIRECT_SESSION, error.message)
    }

    @Test
    fun `with a direct session the memory browser lists through it`() = runTest {
        val registry = DesktopLocalAppServerClientRegistry()
        val lease = registry.install(PagedMemoryClient())
        val listing = DesktopWorkspaceSources(registry).memfs().list("agent-1")
        assertEquals(listOf("system/persona.md"), listing.files.map { it.path })
        lease.close()
    }

    @Test
    fun `over an iroh host the memory browser lists through its workspace relay`() = runTest {
        val host = RelayingHost()
        val listing = DesktopWorkspaceSources(DesktopLocalAppServerClientRegistry(), irohTransport = { host }).memfs().list("agent-1")
        assertEquals(listOf("system/human/communication_style.md"), listing.files.map { it.path })
        assertEquals(listOf("memfs.list"), host.methods)
    }

    /** An Iroh transport whose host relays `memfs.list` (letta-mobile-bzvro.37). */
    private class RelayingHost : NoOpChannelTransport() {
        val methods = mutableListOf<String>()

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            return AppServerInboundFrame.AdminRpcResponse(
                requestId = "admin-1",
                success = true,
                result = Json.parseToJsonElement(
                    """{"frames":[{"type":"list_memory_response","request_id":"h","entries":[{"relative_path":"system/human/communication_style.md","is_system":true,"content":"x","size":1}],"done":true,"success":true}]}""",
                ),
            )
        }
    }

    private class PagedMemoryClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun workspaceRequest(command: AppServerWorkspaceCommand): List<JsonObject> = listOf(
            Json.parseToJsonElement(
                """{"type":"list_memory_response","request_id":"${command.requestId}","entries":[{"relative_path":"system/persona.md","is_system":true,"content":"x","size":1}],"done":true,"total":1,"success":true}""",
            ).jsonObject,
        )

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unsupported()
        override suspend fun input(command: AppServerCommand.Input): Unit = unsupported()
        override suspend fun sync(command: AppServerCommand.Sync) = unsupported()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unsupported()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unsupported()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unsupported()
        private fun unsupported(): Nothing = error("Unexpected App Server operation")
    }
}
