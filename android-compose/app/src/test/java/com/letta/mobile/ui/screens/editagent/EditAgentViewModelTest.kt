package com.letta.mobile.ui.screens.editagent

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.ModelRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.screens.settings.ClientModeConnectionTester
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class EditAgentViewModelTest {

    private lateinit var fakeAgentRepo: FakeAgentRepo
    private lateinit var fakeBlockRepo: FakeBlockRepo
    private lateinit var fakeToolRepo: FakeToolRepo
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockClientModeConnectionTester: ClientModeConnectionTester
    private lateinit var mockMessageRepository: MessageRepository
    private lateinit var mockModelRepository: ModelRepository
    private lateinit var llmModelsFlow: MutableStateFlow<List<LlmModel>>
    private lateinit var embeddingModelsFlow: MutableStateFlow<List<EmbeddingModel>>
    private lateinit var viewModel: EditAgentViewModel
    private val testDispatcher = UnconfinedTestDispatcher()
    private var clientModeEnabled: Boolean = false
    private var clientModeBaseUrl: String = ""
    private var clientModeApiKey: String? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeAgentRepo = FakeAgentRepo()
        fakeBlockRepo = FakeBlockRepo()
        fakeToolRepo = FakeToolRepo()
        clientModeEnabled = false
        clientModeBaseUrl = ""
        clientModeApiKey = null
        mockSettingsRepository = mockk(relaxed = true)
        mockClientModeConnectionTester = mockk(relaxed = true)
        mockMessageRepository = mockk(relaxed = true)
        mockModelRepository = mockk(relaxed = true)
        llmModelsFlow = MutableStateFlow(
            listOf(
                LlmModel(id = "m1", name = "Letta Free", handle = "letta/letta-free", providerType = "openai"),
                LlmModel(id = "m2", name = "Claude Sonnet", handle = "anthropic/claude-3-5-sonnet", providerType = "anthropic"),
                LlmModel(id = "m3", name = "MiniMax M1", handle = "openrouter/minimax-m1", providerType = "minimax"),
            )
        )
        embeddingModelsFlow = MutableStateFlow(
            listOf(
                EmbeddingModel(id = "e1", name = "text-embedding-3-small", handle = "openai/text-embedding-3-small", providerType = "openai"),
            )
        )
        every { mockModelRepository.llmModels } returns llmModelsFlow.asStateFlow()
        every { mockModelRepository.embeddingModels } returns embeddingModelsFlow.asStateFlow()
        every { mockSettingsRepository.observeClientModeEnabled() } answers { flowOf(clientModeEnabled) }
        every { mockSettingsRepository.observeClientModeBaseUrl() } answers { flowOf(clientModeBaseUrl) }
        every { mockSettingsRepository.getClientModeApiKey() } answers { clientModeApiKey }
        coEvery { mockSettingsRepository.setClientModeEnabled(any()) } answers {
            clientModeEnabled = firstArg()
        }
        coEvery { mockSettingsRepository.setClientModeBaseUrl(any()) } answers {
            clientModeBaseUrl = firstArg()
        }
        every { mockSettingsRepository.setClientModeApiKey(any()) } answers {
            clientModeApiKey = firstArg()
        }
        coEvery { mockClientModeConnectionTester.test(any(), any()) } returns Result.success(Unit)
        val savedState = SavedStateHandle(mapOf("agentId" to "a1"))
        viewModel = EditAgentViewModel(
            savedState,
            fakeAgentRepo,
            fakeBlockRepo,
            mockMessageRepository,
            mockModelRepository,
            fakeToolRepo,
            mockSettingsRepository,
            mockClientModeConnectionTester,
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadAgent populates all fields`() = runTest {
        viewModel.loadAgent()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("Test Agent", state.data.name)
            assertEquals(2, state.data.blocks.size)
            assertEquals("persona value", state.data.blocks.first { it.label == "persona" }.value)
            assertEquals("human value", state.data.blocks.first { it.label == "human" }.value)
            assertEquals("stateful", state.data.agentType)
            assertEquals("openai", state.data.providerType)
            assertEquals(4096, state.data.maxOutputTokens)
            assertEquals(1, state.data.attachedTools.size)
            assertEquals(2, state.data.availableTools.size)
        }
    }

    @Test
    fun `loadAgent sets Error on failure`() = runTest {
        fakeAgentRepo.shouldFail = true
        viewModel.loadAgent()
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    @Test
    fun `updateName changes only name`() = runTest {
        viewModel.loadAgent()
        viewModel.updateName("New Name")
        viewModel.uiState.test {
            assertEquals("New Name", (awaitItem() as UiState.Success).data.name)
        }
    }

    @Test
    fun `updateBlockValue changes matching block`() = runTest {
        viewModel.loadAgent()
        viewModel.updateBlockValue("persona", "New persona")
        viewModel.uiState.test {
            val state = (awaitItem() as UiState.Success).data
            assertEquals("New persona", state.blocks.first { it.label == "persona" }.value)
        }
    }

    @Test
    fun `saveAgent calls onSuccess`() = runTest {
        viewModel.loadAgent()
        var called = false
        viewModel.saveAgent { called = true }
        assertTrue(called)
        assertEquals(4096, fakeAgentRepo.lastUpdateParams?.modelSettings?.maxOutputTokens)
        assertEquals("openai", fakeAgentRepo.lastUpdateParams?.modelSettings?.providerType)
    }

    @Test
    fun `saveAgent persists edited max output tokens`() = runTest {
        viewModel.loadAgent()
        viewModel.updateMaxOutputTokens(8192)

        viewModel.saveAgent {}

        assertEquals(8192, fakeAgentRepo.lastUpdateParams?.modelSettings?.maxOutputTokens)
    }

    @Test
    fun `updateProviderType changes provider type`() = runTest {
        viewModel.loadAgent()
        viewModel.updateProviderType("anthropic")

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("anthropic", state.data.providerType)
        }
    }

    @Test
    fun `saveAgent persists edited provider type`() = runTest {
        viewModel.loadAgent()
        viewModel.updateProviderType("anthropic")

        viewModel.saveAgent {}

        assertEquals("anthropic", fakeAgentRepo.lastUpdateParams?.modelSettings?.providerType)
    }

    @Test
    fun `updateModel stores selected handle and syncs provider type`() = runTest {
        viewModel.loadAgent()

        viewModel.updateModel("anthropic/claude-3-5-sonnet")

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("anthropic/claude-3-5-sonnet", state.data.model)
            assertEquals("anthropic", state.data.providerType)
        }
    }

    @Test
    fun `saveAgent persists selected model handle`() = runTest {
        viewModel.loadAgent()
        viewModel.updateModel("anthropic/claude-3-5-sonnet")

        viewModel.saveAgent {}

        assertEquals("anthropic/claude-3-5-sonnet", fakeAgentRepo.lastUpdateParams?.model)
        assertEquals("anthropic", fakeAgentRepo.lastUpdateParams?.modelSettings?.providerType)
    }

    @Test
    fun `updateModel normalizes unsupported provider type from handle`() = runTest {
        viewModel.loadAgent()

        viewModel.updateModel("openrouter/minimax-m1")

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("openrouter/minimax-m1", state.data.model)
            assertEquals("openrouter", state.data.providerType)
        }
    }

    @Test
    fun `saveAgent omits unsupported provider type and uses supported handle prefix`() = runTest {
        viewModel.loadAgent()

        viewModel.updateModel("openrouter/minimax-m1")
        viewModel.saveAgent {}

        assertEquals("openrouter/minimax-m1", fakeAgentRepo.lastUpdateParams?.model)
        assertEquals("openrouter", fakeAgentRepo.lastUpdateParams?.modelSettings?.providerType)
    }

    @Test
    fun `saveAgent derives provider type from handle when loaded provider is unsupported`() = runTest {
        fakeAgentRepo.loadedModel = "openrouter/minimax-m1"
        fakeAgentRepo.loadedProviderType = "minimax"

        viewModel.loadAgent()
        viewModel.saveAgent {}

        assertEquals("openrouter/minimax-m1", fakeAgentRepo.lastUpdateParams?.model)
        assertEquals("openrouter", fakeAgentRepo.lastUpdateParams?.modelSettings?.providerType)
    }

    @Test
    fun `loadAgent includes client mode settings`() = runTest {
        clientModeEnabled = true
        clientModeBaseUrl = "http://192.168.50.90:8407"
        clientModeApiKey = "secret"

        viewModel.loadAgent()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.clientModeEnabled)
            assertEquals("http://192.168.50.90:8407", state.data.clientModeBaseUrl)
            assertEquals("secret", state.data.clientModeApiKey)
        }
    }

    @Test
    fun `saveAgent persists client mode settings`() = runTest {
        viewModel.loadAgent()
        viewModel.updateClientModeEnabled(true)
        viewModel.updateClientModeBaseUrl("http://192.168.50.90:8407")
        viewModel.updateClientModeApiKey("lettaSecurePass123")

        viewModel.saveAgent {}

        assertTrue(clientModeEnabled)
        assertEquals("http://192.168.50.90:8407", clientModeBaseUrl)
        assertEquals("lettaSecurePass123", clientModeApiKey)
    }

    @Test
    fun `testClientModeConnection sets success state`() = runTest {
        viewModel.loadAgent()
        viewModel.updateClientModeBaseUrl("http://192.168.50.90:8407")
        coEvery { mockClientModeConnectionTester.test(any(), any()) } returns Result.success(Unit)

        viewModel.testClientModeConnection()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.clientModeConnectionState is ClientModeConnectionState.Success)
        }
    }

    @Test
    fun `attachTool delegates to repository`() = runTest {
        viewModel.attachTool("t2")

        assertEquals(listOf("t2"), fakeToolRepo.attachedToolIds)
    }

    @Test
    fun `detachTool delegates to repository`() = runTest {
        viewModel.detachTool("t1")

        assertEquals(listOf("t1"), fakeToolRepo.detachedToolIds)
    }

    @Test
    fun `addBlock forwards description and limit`() = runTest {
        viewModel.addBlock("memory", "value", "notes", 512)

        assertEquals("memory", fakeBlockRepo.lastCreatedParams?.label)
        assertEquals("value", fakeBlockRepo.lastCreatedParams?.value)
        assertEquals("notes", fakeBlockRepo.lastCreatedParams?.description)
        assertEquals(512, fakeBlockRepo.lastCreatedParams?.limit)
    }

    @Test
    fun `attachExistingBlock delegates to repository`() = runTest {
        viewModel.attachExistingBlock("existing-block")

        assertEquals(listOf("existing-block"), fakeBlockRepo.attachedExistingBlockIds)
    }

    @Test
    fun `deleteBlock detaches without deleting shared block`() = runTest {
        viewModel.deleteBlock("block-1")

        assertEquals(listOf("block-1"), fakeBlockRepo.detachedBlockIds)
        assertTrue(fakeBlockRepo.deletedBlockIds.isEmpty())
    }

    @Test
    fun `saveAgent forwards edited block metadata`() = runTest {
        viewModel.loadAgent()
        viewModel.updateBlockDescription("persona", "updated description")
        viewModel.updateBlockLimit("persona", 256)
        viewModel.saveAgent {}

        assertEquals("persona", fakeBlockRepo.lastUpdatedLabel)
        assertEquals("updated description", fakeBlockRepo.lastUpdatedParams?.description)
        assertEquals(256, fakeBlockRepo.lastUpdatedParams?.limit)
        assertEquals("persona value", fakeBlockRepo.lastUpdatedParams?.value)
    }

    @Test
    fun `saveAgent sets Error on failure`() = runTest {
        viewModel.loadAgent()
        fakeAgentRepo.shouldFail = true
        viewModel.saveAgent {}
        viewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }

    private class FakeAgentRepo : AgentRepository(FakeAgentApi(), mockk(relaxed = true)) {
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())
        override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
        var shouldFail = false
        var lastUpdateParams: AgentUpdateParams? = null
        var loadedModel: String = "letta/letta-free"
        var loadedProviderType: String? = "openai"

        override fun getAgent(id: String): Flow<Agent> = flow {
            if (shouldFail) throw Exception("Load failed")
            emit(Agent(
                id = "a1",
                name = "Test Agent",
                description = "A test agent",
                model = loadedModel,
                embedding = "openai/text-embedding-3-small",
                tags = listOf("test"),
                system = "System prompt",
                agentType = "stateful",
                enableSleeptime = true,
                tools = listOf(TestData.tool(id = "t1", name = "attached_tool")),
                modelSettings = com.letta.mobile.data.model.ModelSettings(
                    providerType = loadedProviderType,
                    temperature = 0.9,
                    maxOutputTokens = 4096,
                    parallelToolCalls = false,
                ),
                blocks = listOf(
                    TestData.block(label = "persona", value = "persona value"),
                    TestData.block(label = "human", value = "human value"),
                )
            ))
        }
        override suspend fun refreshAgents() {}
        override suspend fun createAgent(params: AgentCreateParams): Agent = TestData.agent()
        override suspend fun updateAgent(id: String, params: AgentUpdateParams): Agent {
            if (shouldFail) throw Exception("Update failed")
            lastUpdateParams = params
            return Agent(
                id = id,
                name = params.name ?: "Test Agent",
                description = params.description,
                model = params.model,
                embedding = params.embedding,
                system = params.system,
                tags = params.tags ?: emptyList(),
                enableSleeptime = params.enableSleeptime,
                agentType = "stateful",
                modelSettings = params.modelSettings,
            )
        }
        override suspend fun deleteAgent(id: String) {}
    }

    private class FakeBlockRepo : BlockRepository(FakeBlockApi()) {
        var lastCreatedParams: BlockCreateParams? = null
        var lastUpdatedLabel: String? = null
        var lastUpdatedParams: BlockUpdateParams? = null
        val attachedExistingBlockIds = mutableListOf<String>()
        val detachedBlockIds = mutableListOf<String>()
        val deletedBlockIds = mutableListOf<String>()

        override suspend fun createBlock(params: BlockCreateParams): Block {
            lastCreatedParams = params
            return TestData.block(id = "new-block", label = params.label, value = params.value)
        }

        override suspend fun updateAgentBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
            lastUpdatedLabel = blockLabel
            lastUpdatedParams = params
            return Block(
                id = "updated-block",
                label = blockLabel,
                value = params.value ?: "",
                description = params.description,
                limit = params.limit,
            )
        }

        override suspend fun updateGlobalBlock(
            blockId: String,
            params: BlockUpdateParams,
            clearDescription: Boolean,
            clearLimit: Boolean,
        ): Block {
            return Block(
                id = blockId,
                label = "global",
                value = params.value ?: "",
                description = if (clearDescription) null else params.description,
                limit = if (clearLimit) null else params.limit,
            )
        }

        override suspend fun attachBlock(agentId: String, blockId: String) {
            attachedExistingBlockIds.add(blockId)
        }

        override suspend fun detachBlock(agentId: String, blockId: String) {
            detachedBlockIds.add(blockId)
        }

        override suspend fun deleteBlock(blockId: String) {
            deletedBlockIds.add(blockId)
        }
    }

    private class FakeToolRepo : ToolRepository(FakeToolApi()) {
        private val availableTools = MutableStateFlow(
            listOf(
                TestData.tool(id = "t1", name = "attached_tool"),
                TestData.tool(id = "t2", name = "second_tool"),
            )
        )
        val attachedToolIds = mutableListOf<String>()
        val detachedToolIds = mutableListOf<String>()

        override fun getTools(): StateFlow<List<Tool>> = availableTools
        override suspend fun refreshTools() {}
        override suspend fun attachTool(agentId: String, toolId: String) {
            attachedToolIds.add(toolId)
        }
        override suspend fun detachTool(agentId: String, toolId: String) {
            detachedToolIds.add(toolId)
        }
    }
}
