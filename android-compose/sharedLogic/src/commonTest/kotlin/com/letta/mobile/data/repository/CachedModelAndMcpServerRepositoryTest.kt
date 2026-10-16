package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.repository.api.McpServerIrohSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.repository.api.ModelIrohSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CachedMcpServerRepositoryTest {

    @Test
    fun createServerUpsertsReturnedServerWithoutListRefresh() = runTest {
        val remote = FakeMcpRemoteSource()
        val repository = CachedMcpServerRepository(remote.bound())

        val created = repository.createServer(
            McpServerCreateParams(serverName = "New Server", config = JsonObject(emptyMap())),
        )

        assertEquals("server-1", created.id.value)
        assertEquals(listOf(created), repository.servers.value)
        assertEquals(listOf("createMcpServer"), remote.calls)
    }

    @Test
    fun deleteServerRemovesServerAndToolsWithoutListRefresh() = runTest {
        val remote = FakeMcpRemoteSource()
        val repository = CachedMcpServerRepository(remote.bound())
        val serverId = McpServerId("server-1")
        repository.refreshServers()
        repository.refreshServerTools(serverId)

        repository.deleteServer(serverId)

        assertEquals(emptyList(), repository.servers.value)
        assertEquals(listOf("listMcpServers", "listMcpServerTools", "deleteMcpServer"), remote.calls)
    }

    @Test
    fun resyncServerToolsReturnsMutationWhenFollowUpListFails() = runTest {
        val remote = FakeMcpRemoteSource(listToolsShouldFail = true)
        val repository = CachedMcpServerRepository(remote.bound())
        val serverId = McpServerId("server-1")
        repository.refreshServers()

        val result = repository.resyncServerTools(serverId)

        assertEquals(McpServerResyncResult(), result)
        assertEquals(listOf("listMcpServers", "refreshMcpServerTools", "listMcpServerTools"), remote.calls)
    }

    @Test
    fun fetchAllMcpToolsInIrohModeDoesNotCallRemote() = runTest {
        val remote = FakeMcpRemoteSource(shouldThrowIfCalled = true)
        val irohSource = FakeMcpIrohSource(servers = listOf(testMcpServer("server-1")))
        val repository = CachedMcpServerRepository(remote.bound(), irohSource)

        val tools = repository.fetchAllMcpTools()

        assertEquals(emptyList(), tools)
        assertEquals(listOf("server-1"), repository.servers.value.map { it.id.value })
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun unsupportedOperationsInIrohModeDoNotCallRemote() = runTest {
        val remote = FakeMcpRemoteSource(shouldThrowIfCalled = true)
        val irohSource = FakeMcpIrohSource()
        val repository = CachedMcpServerRepository(remote.bound(), irohSource)
        val serverId = McpServerId("server-1")

        assertFailsWith<UnsupportedOperationException> { repository.refreshServerTools(serverId) }
        assertFailsWith<UnsupportedOperationException> { repository.resyncServerTools(serverId) }
        assertFailsWith<UnsupportedOperationException> {
            repository.runServerTool(serverId, ToolId("tool-1"), McpToolExecuteParams())
        }
        assertFailsWith<UnsupportedOperationException> {
            repository.createServer(McpServerCreateParams("name", JsonObject(emptyMap())))
        }
        assertFailsWith<UnsupportedOperationException> {
            repository.updateServer(serverId, McpServerUpdateParams(serverName = "updated"))
        }
        assertFailsWith<UnsupportedOperationException> { repository.deleteServer(serverId) }
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun fetchAllMcpToolsRethrowsCancellation() = runTest {
        val remote = FakeMcpRemoteSource(cancelOnListTools = true)
        val repository = CachedMcpServerRepository(remote.bound())
        repository.refreshServers()

        assertFailsWith<CancellationException> { repository.fetchAllMcpTools() }
    }

    private class FakeMcpRemoteSource(
        private val listToolsShouldFail: Boolean = false,
        private val shouldThrowIfCalled: Boolean = false,
        private val cancelOnListTools: Boolean = false,
    ) : McpServerRemoteSource {
        val calls = mutableListOf<String>()
        private val servers = mutableListOf(testMcpServer("server-1"))
        private val toolsByServer = mutableMapOf<String, List<Tool>>(
            "server-1" to listOf(Tool(id = ToolId("tool-1"), name = "tool")),
        )

        override suspend fun listMcpServers(limit: Int?, offset: Int?): List<McpServer> {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("listMcpServers")
            return servers.toList()
        }

        override suspend fun createMcpServer(params: McpServerCreateParams): McpServer {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("createMcpServer")
            val created = testMcpServer("server-1")
            servers += created
            return created
        }

        override suspend fun updateMcpServer(serverId: String, params: McpServerUpdateParams): McpServer {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("updateMcpServer")
            return testMcpServer(serverId)
        }

        override suspend fun deleteMcpServer(serverId: String) {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("deleteMcpServer")
            servers.removeAll { it.id.value == serverId }
        }

        override suspend fun listMcpServerTools(serverId: String): List<Tool> {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("listMcpServerTools")
            if (cancelOnListTools) throw CancellationException("cancelled")
            if (listToolsShouldFail) error("list tools failed")
            return toolsByServer[serverId].orEmpty()
        }

        override suspend fun refreshMcpServerTools(serverId: String): McpServerResyncResult {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("refreshMcpServerTools")
            return McpServerResyncResult()
        }

        override suspend fun runMcpServerTool(
            serverId: String,
            toolId: String,
            params: McpToolExecuteParams,
        ): McpToolExecutionResult {
            if (shouldThrowIfCalled) error("remote must not be called")
            calls.add("runMcpServerTool")
            return McpToolExecutionResult(status = "success")
        }
    }

    private class FakeMcpIrohSource(
        private val servers: List<McpServer> = emptyList(),
    ) : McpServerIrohSource {
        override fun shouldUseIroh(): Boolean = true

        override suspend fun listMcpServers(): List<McpServer> = servers
    }
}

private fun testMcpServer(id: String): McpServer =
    McpServer(id = McpServerId(id), serverName = id, serverUrl = "https://example.com")

@OptIn(ExperimentalCoroutinesApi::class)
class CachedModelRepositoryTest {

    @Test
    fun refreshLlmModelsUsesIrohSourceWhenActive() = runTest {
        val remote = FakeModelRemoteSource(llmModels = listOf(model("remote/model")))
        val iroh = FakeModelIrohSource(llmModels = listOf(model("iroh/model")))
        val repository = CachedModelRepository(remote.bound(), irohModelSource = iroh)

        repository.refreshLlmModels()

        assertEquals(listOf("iroh/model"), repository.llmModels.value.map { it.handle })
        assertTrue(remote.llmCalls == 0)
    }

    @Test
    fun refreshLlmModelsReplacesPriorSnapshotAndDoesNotAccumulateDuplicates() = runTest {
        val duplicate = model("openai/gpt-4o")
        val remote = FakeModelRemoteSource(
            llmModels = listOf(duplicate, duplicate, model("anthropic/claude")),
        )
        val repository = CachedModelRepository(remote.bound())

        repository.refreshLlmModels()
        assertEquals(
            listOf("openai/gpt-4o", "anthropic/claude"),
            repository.llmModels.value.map { it.handle },
        )

        remote.llmModels = listOf(model("google/gemini"))
        repository.refreshLlmModels()

        assertEquals(listOf("google/gemini"), repository.llmModels.value.map { it.handle })
    }

    @Test
    fun refreshEmbeddingModelsUsesRemoteWhenNotLocalOrIroh() = runTest {
        val remote = FakeModelRemoteSource(
            embeddingModels = listOf(EmbeddingModel(id = "embed-1", name = "Embed", handle = "openai/embed")),
        )
        val repository = CachedModelRepository(remote.bound())

        repository.refreshEmbeddingModels()

        assertEquals(listOf("openai/embed"), repository.embeddingModels.value.map { it.handle })
        assertEquals(1, remote.embeddingCalls)
    }

    private fun model(handle: String): LlmModel {
        val provider = handle.substringBefore('/')
        return LlmModel(
            id = handle,
            name = handle.substringAfter('/'),
            handle = handle,
            providerType = provider,
        )
    }

    private class FakeModelRemoteSource(
        var llmModels: List<LlmModel> = emptyList(),
        private val embeddingModels: List<EmbeddingModel> = emptyList(),
    ) : ModelRemoteSource {
        var llmCalls = 0
        var embeddingCalls = 0

        override suspend fun listLlmModels(): List<LlmModel> {
            llmCalls += 1
            return llmModels
        }

        override suspend fun listEmbeddingModels(): List<EmbeddingModel> {
            embeddingCalls += 1
            return embeddingModels
        }
    }

    private class FakeModelIrohSource(
        private val llmModels: List<LlmModel> = emptyList(),
        private val embeddingModels: List<EmbeddingModel> = emptyList(),
    ) : ModelIrohSource {
        override fun shouldUseIroh(): Boolean = true

        override suspend fun listLlmModels(): List<LlmModel> = llmModels

        override suspend fun listEmbeddingModels(): List<EmbeddingModel> = embeddingModels
    }

}
