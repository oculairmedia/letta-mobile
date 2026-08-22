package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.McpToolExecuteParams
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.LocalRuntimeModelSource
import com.letta.mobile.data.repository.api.McpServerIrohSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.repository.api.ModelIrohSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
        val repository = CachedMcpServerRepository(remote)

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
        val repository = CachedMcpServerRepository(remote)
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
        val repository = CachedMcpServerRepository(remote)
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
        val repository = CachedMcpServerRepository(remote, irohSource)

        val tools = repository.fetchAllMcpTools()

        assertEquals(emptyList(), tools)
        assertEquals(listOf("server-1"), repository.servers.value.map { it.id.value })
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun unsupportedOperationsInIrohModeDoNotCallRemote() = runTest {
        val remote = FakeMcpRemoteSource(shouldThrowIfCalled = true)
        val irohSource = FakeMcpIrohSource()
        val repository = CachedMcpServerRepository(remote, irohSource)
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
        val repository = CachedMcpServerRepository(remote)
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
    fun refreshLlmModelsPrefersLocalRuntimeSource() = runTest {
        val remote = FakeModelRemoteSource(llmModels = listOf(model("remote/model")))
        val local = FakeLocalRuntimeModelSource(llmModels = listOf(model("local/model")))
        val settings = MinimalSettingsRepository(
            LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
            ),
        )
        val repository = CachedModelRepository(remote, local, settings)

        repository.refreshLlmModels()

        assertEquals(listOf("local/model"), repository.llmModels.value.map { it.handle })
        assertTrue(remote.llmCalls == 0)
    }

    @Test
    fun refreshLlmModelsUsesIrohSourceWhenActive() = runTest {
        val remote = FakeModelRemoteSource(llmModels = listOf(model("remote/model")))
        val iroh = FakeModelIrohSource(llmModels = listOf(model("iroh/model")))
        val repository = CachedModelRepository(remote, irohModelSource = iroh)

        repository.refreshLlmModels()

        assertEquals(listOf("iroh/model"), repository.llmModels.value.map { it.handle })
        assertTrue(remote.llmCalls == 0)
    }

    @Test
    fun refreshEmbeddingModelsUsesRemoteWhenNotLocalOrIroh() = runTest {
        val remote = FakeModelRemoteSource(
            embeddingModels = listOf(EmbeddingModel(id = "embed-1", name = "Embed", handle = "openai/embed")),
        )
        val repository = CachedModelRepository(remote)

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
        private val llmModels: List<LlmModel> = emptyList(),
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

    private class FakeLocalRuntimeModelSource(
        private val llmModels: List<LlmModel>,
    ) : LocalRuntimeModelSource {
        override suspend fun listLlmModels(): List<LlmModel> = llmModels
    }

    private class MinimalSettingsRepository(
        initial: LettaConfig,
    ) : ISettingsRepository {
        private val config = MutableStateFlow<LettaConfig?>(initial)

        override val configs: StateFlow<List<LettaConfig>> = MutableStateFlow(listOf(initial)).asStateFlow()
        override val activeConfig: StateFlow<LettaConfig?> = config.asStateFlow()
        override val activeConfigChanges: Flow<LettaConfig> = flowOf(initial)
        override val favoriteAgentId: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val adminAgentId: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val lastChatSelection: StateFlow<LastChatSelection?> = MutableStateFlow(null).asStateFlow()
        override val huggingFaceToken: StateFlow<String?> = MutableStateFlow(null).asStateFlow()

        override fun getActiveConfig(): Flow<LettaConfig?> = activeConfig
        override suspend fun saveConfig(config: LettaConfig) = Unit
        override suspend fun setActiveConfigId(id: String) = Unit
        override suspend fun deleteConfig(id: String) = Unit
        override suspend fun clearAllData() = Unit
        override suspend fun setHuggingFaceToken(token: String?) = Unit
        override fun getTheme(): Flow<AppTheme> = flowOf(AppTheme.SYSTEM)
        override fun getThemePreset(): Flow<ThemePreset> = flowOf(ThemePreset.DEFAULT)
        override fun getDynamicColor(): Flow<Boolean> = flowOf(false)
        override fun observeResumeRecentConversation(): Flow<Boolean> = flowOf(false)
        override fun getPinnedAgentIds(): Flow<Set<String>> = flowOf(emptySet())
        override fun getPinnedAgentOrder(): Flow<List<String>> = flowOf(emptyList())
        override fun getPinnedConversationIds(): Flow<Set<String>> = flowOf(emptySet())
        override fun setLastChatSelection(agentId: String, agentName: String?, conversationId: String?) = Unit
        override suspend fun setConversationPinned(conversationId: String, pinned: Boolean) = Unit
        override fun setFavoriteAgentId(agentId: String?) = Unit
        override suspend fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
        override suspend fun setPinnedAgentOrder(order: List<String>) = Unit
        override fun getPinnedProjectIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun setProjectPinned(projectId: String, pinned: Boolean) = Unit
        override fun getPinnedShortcutOrder(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setPinnedShortcutOrder(order: List<String>) = Unit
        override suspend fun addPinnedShortcut(name: String) = Unit
        override suspend fun removePinnedShortcut(name: String) = Unit
        override fun getPinnedItemsOrder(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setPinnedItemsOrder(order: List<String>) = Unit
        override fun getPinnedAgentNames(): Flow<Map<String, String>> = flowOf(emptyMap())
        override suspend fun upsertPinnedAgentName(id: String, name: String) = Unit
        override suspend fun removePinnedAgentName(id: String) = Unit
        override fun getChatBackgroundKey(): Flow<String> = flowOf("default")
        override suspend fun setChatBackgroundKey(key: String) = Unit
        override fun getChatFontScale(): Flow<Float> = flowOf(1f)
        override suspend fun setChatFontScale(scale: Float) = Unit
        override fun getEnableProjects(): Flow<Boolean> = flowOf(false)
        override fun getHapticsEnabled(): Flow<Boolean> = flowOf(true)
        override suspend fun setTheme(theme: AppTheme) = Unit
        override suspend fun setThemePreset(themePreset: ThemePreset) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setEnableProjects(enabled: Boolean) = Unit
        override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
    }
}
