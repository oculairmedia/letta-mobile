package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDefaultConfig
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AgentListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepository: IAgentRepository
    private lateinit var settingsRepository: ISettingsRepository
    private lateinit var toolRepository: IToolRepository
    private lateinit var modelRepository: IModelRepository
    private lateinit var runtimeStatusProvider: FakeRuntimeStatusProvider
    private lateinit var embeddedModelRepository: FakeEmbeddedModelRepository
    private val activeConfigFlow = MutableStateFlow<LettaConfig?>(null)
    private lateinit var viewModel: AgentListViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        agentRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        runtimeStatusProvider = FakeRuntimeStatusProvider()
        embeddedModelRepository = FakeEmbeddedModelRepository()
        activeConfigFlow.value = null

        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        every { settingsRepository.activeConfig } returns activeConfigFlow
        every { settingsRepository.activeConfigChanges } returns emptyFlow()
        every { settingsRepository.favoriteAgentId } returns MutableStateFlow(null)
        every { settingsRepository.getPinnedAgentIds() } returns MutableStateFlow(emptySet())
        every { toolRepository.getTools() } returns MutableStateFlow(
            listOf(
                Tool(id = ToolId("t1"), name = "tool_one"),
                Tool(id = ToolId("t2"), name = "tool_two"),
            )
        )
        every { modelRepository.llmModels } returns MutableStateFlow(
            listOf(LlmModel(id = "m1", name = "openai/gpt-4o", providerType = "openai"))
        )
        every { modelRepository.embeddingModels } returns MutableStateFlow(
            listOf(EmbeddingModel(id = "e1", name = "openai/text-embedding-3-small", providerType = "openai"))
        )

        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAvailableTools populates create form tool source`() = runTest {
        viewModel.loadAvailableTools()

        assertEquals(2, viewModel.uiState.value.availableTools.size)
        assertEquals("tool_one", viewModel.uiState.value.availableTools.first().name)
    }

    @Test
    fun `loadAvailableModels populates create form model sources`() = runTest {
        viewModel.loadAvailableModels()

        assertEquals(1, viewModel.uiState.value.llmModels.size)
        assertEquals(1, viewModel.uiState.value.embeddingModels.size)
        assertEquals("openai/gpt-4o", viewModel.uiState.value.llmModels.first().name)
    }

    @Test
    fun `createAgent forwards tool ids and include base tools`() = runTest {
        val paramsSlot = slot<AgentCreateParams>()
        coEvery { agentRepository.createAgent(capture(paramsSlot)) } returns Agent(id = AgentId("a1"), name = "Agent")

        var createdId: AgentId? = null
        viewModel.createAgent(
            AgentCreateParams(
                name = "Agent",
                model = "openai/gpt-4o",
                embedding = "openai/text-embedding-3-small",
                toolIds = listOf(ToolId("t1"), ToolId("t2")),
                includeBaseTools = true,
            )
        ) { createdId = it }

        assertEquals(listOf(ToolId("t1"), ToolId("t2")), paramsSlot.captured.toolIds)
        assertTrue(paramsSlot.captured.includeBaseTools == true)
        assertEquals(AgentId("a1"), createdId)
        coVerify(exactly = 1) { agentRepository.createAgent(any()) }
    }

    @Test
    fun `createAgent local ready stamps runtime metadata and disables unsupported tools`() = runTest {
        activeConfigFlow.value = localConfig()
        embeddedModelRepository.catalogFlow.value = listOf(downloadedModel())
        val paramsSlot = slot<AgentCreateParams>()
        coEvery { agentRepository.createLocalAgent(capture(paramsSlot)) } returns Agent(id = AgentId("local-agent-test"), name = "Local")

        var createdId: AgentId? = null
        viewModel.createAgent(
            // No model picked in the dialog → falls back to the config-level
            // selection (a picked model would win; see the dedicated test).
            AgentCreateParams(
                name = "Local",
                embedding = "remote/embed",
                toolIds = listOf(ToolId("t1")),
                includeBaseTools = true,
                enableSleeptime = true,
            ),
            AgentCreateRuntimeOption.LOCAL_LETTACODE,
        ) { createdId = it }

        val captured = paramsSlot.captured
        assertEquals(AgentId("local-agent-test"), createdId)
        assertEquals("lmstudio/google/gemma-test-litert-lm", captured.model)
        assertEquals(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime, captured.modelSettings?.providerType)
        assertEquals(false, captured.modelSettings?.parallelToolCalls)
        assertNull(captured.toolIds)
        assertEquals(false, captured.includeBaseTools)
        assertEquals(false, captured.enableSleeptime)
        assertEquals(false, captured.parallelToolCalls)
        assertEquals(
            LocalAgentRuntimeMetadata.LocalLettaCodeRuntime,
            captured.metadata?.get(LocalAgentRuntimeMetadata.RuntimeKey)?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "${LocalAgentRuntimeMetadata.LocalLettaCodeRuntime}:local-config",
            captured.metadata?.get(LocalAgentRuntimeMetadata.RuntimeIdKey)?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "google/gemma-test-litert-lm",
            captured.metadata?.get(LocalAgentRuntimeMetadata.LocalModelHandleKey)?.jsonPrimitive?.contentOrNull,
        )
        coVerify(exactly = 0) { agentRepository.createAgent(any()) }
        coVerify(exactly = 1) { agentRepository.createLocalAgent(any()) }
    }

    // letta-mobile-3icw7: a model picked in the create dialog (endpoint or
    // downloaded catalog id) wins over the config-level default.
    @Test
    fun `createAgent local picked model overrides config default`() = runTest {
        activeConfigFlow.value = localConfig()
        embeddedModelRepository.catalogFlow.value = listOf(downloadedModel())
        val paramsSlot = slot<AgentCreateParams>()
        coEvery { agentRepository.createLocalAgent(capture(paramsSlot)) } returns Agent(id = AgentId("local-agent-test"), name = "Local")

        viewModel.createAgent(
            AgentCreateParams(name = "Local", model = "MiniMax-M3"),
            AgentCreateRuntimeOption.LOCAL_LETTACODE,
        ) {}

        assertEquals("lmstudio/MiniMax-M3", paramsSlot.captured.model)
    }

    @Test
    fun `createAgent local missing prerequisites shows setup copy and does not create`() = runTest {
        activeConfigFlow.value = localConfig(localModelHandle = "local/default", localModelPath = null)
        embeddedModelRepository.catalogFlow.value = emptyList()

        var createdId: AgentId? = null
        viewModel.createAgent(
            AgentCreateParams(name = "Local"),
            AgentCreateRuntimeOption.LOCAL_LETTACODE,
        ) { createdId = it }

        assertNull(createdId)
        assertEquals("Download or import a model in Settings before creating a local agent.", viewModel.uiState.value.error)
        coVerify(exactly = 0) { agentRepository.createAgent(any()) }
        coVerify(exactly = 0) { agentRepository.createLocalAgent(any()) }
    }

    @Test
    fun `createAgent remote behavior unchanged`() = runTest {
        activeConfigFlow.value = localConfig()
        embeddedModelRepository.catalogFlow.value = listOf(downloadedModel())
        val params = AgentCreateParams(
            name = "Remote",
            model = "openai/gpt-4o",
            embedding = "openai/text-embedding-3-small",
            toolIds = listOf(ToolId("t1")),
            includeBaseTools = true,
        )
        val paramsSlot = slot<AgentCreateParams>()
        coEvery { agentRepository.createAgent(capture(paramsSlot)) } returns Agent(id = AgentId("a-remote"), name = "Remote")

        var createdId: AgentId? = null
        viewModel.createAgent(params, AgentCreateRuntimeOption.REMOTE) { createdId = it }

        assertEquals(params, paramsSlot.captured)
        assertEquals(AgentId("a-remote"), createdId)
        coVerify(exactly = 1) { agentRepository.createAgent(any()) }
        coVerify(exactly = 0) { agentRepository.createLocalAgent(any()) }
    }

    @Test
    fun `create form remote missing model fields remains enabled with server default help`() = runTest {
        val validation = validateCreateAgentForm(
            name = "Remote",
            runtimeOption = AgentCreateRuntimeOption.REMOTE,
            localReadiness = LocalLettaCodeCreateReadiness(),
        )

        assertTrue(validation.enabled)
        assertNull(validation.disabledReason)
        assertEquals(
            "Model and embedding are optional; the server default will be used if left blank.",
            remoteCreateAgentModelHelp(model = "", embedding = ""),
        )
    }

    @Test
    fun `create form local missing prerequisites reports exact blocker`() = runTest {
        val validation = validateCreateAgentForm(
            name = "Local",
            runtimeOption = AgentCreateRuntimeOption.LOCAL_LETTACODE,
            localReadiness = LocalLettaCodeCreateReadiness(
                runtimeEnabled = true,
                activeConfigIsLocal = true,
                modelDownloaded = false,
                modelSelected = false,
            ),
        )

        assertFalse(validation.enabled)
        assertEquals("Download or import a model in Settings before creating a local agent.", validation.disabledReason)
    }

    @Test
    fun `create form local ready does not require remote model fields`() = runTest {
        val validation = validateCreateAgentForm(
            name = "Local",
            runtimeOption = AgentCreateRuntimeOption.LOCAL_LETTACODE,
            localReadiness = LocalLettaCodeCreateReadiness(
                runtimeEnabled = true,
                activeConfigIsLocal = true,
                modelDownloaded = true,
                modelSelected = true,
            ),
        )

        assertTrue(validation.enabled)
        assertNull(validation.disabledReason)
    }

    @Test
    fun `create form missing name reports disabled reason`() = runTest {
        val validation = validateCreateAgentForm(
            name = "",
            runtimeOption = AgentCreateRuntimeOption.REMOTE,
            localReadiness = LocalLettaCodeCreateReadiness(),
        )

        assertFalse(validation.enabled)
        assertEquals("Enter an agent name to enable Create.", validation.disabledReason)
    }

    @Test
    fun `importAgent forwards safety flags and returns imported ids`() = runTest {
        coEvery {
            agentRepository.importAgent(
                fileName = "agent.json",
                fileBytes = any(),
                overrideName = "Cloned Agent",
                overrideExistingTools = false,
                projectId = null as ProjectId?,
                stripMessages = true,
            )
        } returns ImportedAgentsResponse(agentIds = listOf("a2"))

        var importedIds: List<String> = emptyList()
        viewModel.importAgent(
            fileName = "agent.json",
            fileBytes = "{}".toByteArray(),
            overrideName = "Cloned Agent",
            overrideExistingTools = false,
            stripMessages = true,
        ) { importedIds = it.agentIds }

        assertEquals(listOf("a2"), importedIds)
        coVerify(exactly = 1) {
            agentRepository.importAgent(
                fileName = "agent.json",
                fileBytes = any(),
                overrideName = "Cloned Agent",
                overrideExistingTools = false,
                projectId = null as ProjectId?,
                stripMessages = true,
            )
        }
    }

    @Test
    fun `getAllTags returns sorted distinct tags from all agents`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1", tags = listOf("beta", "alpha")),
                Agent(id = AgentId("a2"), name = "Agent2", tags = listOf("alpha", "gamma")),
                Agent(id = AgentId("a3"), name = "Agent3", tags = emptyList()),
            )
        )
        viewModel = newViewModel()

        val tags = viewModel.getAllTags()
        assertEquals(listOf("alpha", "beta", "gamma"), tags)
    }

    @Test
    fun `getAllTags returns empty list when no agents have tags`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1"),
                Agent(id = AgentId("a2"), name = "Agent2"),
            )
        )
        viewModel = newViewModel()

        assertTrue(viewModel.getAllTags().isEmpty())
    }

    @Test
    fun `toggleTag adds tag to selectedTags`() = runTest {
        viewModel.toggleTag("alpha")
        assertEquals(setOf("alpha"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `toggleTag removes tag when already selected`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("alpha")
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `toggleTag supports multi-select`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        assertEquals(setOf("alpha", "beta"), viewModel.uiState.value.selectedTags)
    }

    @Test
    fun `clearTags resets selectedTags to empty`() = runTest {
        viewModel.toggleTag("alpha")
        viewModel.toggleTag("beta")
        viewModel.clearTags()
        assertTrue(viewModel.uiState.value.selectedTags.isEmpty())
    }

    @Test
    fun `getFilteredAgents filters by selected tags with AND logic`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1", tags = listOf("alpha", "beta")),
                Agent(id = AgentId("a2"), name = "Agent2", tags = listOf("alpha")),
                Agent(id = AgentId("a3"), name = "Agent3", tags = listOf("beta", "gamma")),
            )
        )
        viewModel = newViewModel()

        // No filter — all agents
        assertEquals(3, viewModel.getFilteredAgents().size)

        // Single tag filter
        viewModel.toggleTag("alpha")
        val alphaFiltered = viewModel.getFilteredAgents()
        assertEquals(2, alphaFiltered.size)
        assertTrue(alphaFiltered.any { it.id.value == "a1" })
        assertTrue(alphaFiltered.any { it.id.value == "a2" })

        // AND logic: alpha + beta — only a1 has both
        viewModel.toggleTag("beta")
        val andFiltered = viewModel.getFilteredAgents()
        assertEquals(1, andFiltered.size)
        assertEquals("a1", andFiltered.first().id.value)
    }

    @Test
    fun `getFilteredAgents combines tag filter with search query`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "ChatBot", tags = listOf("production")),
                Agent(id = AgentId("a2"), name = "HelperBot", tags = listOf("production")),
                Agent(id = AgentId("a3"), name = "TestBot", tags = listOf("staging")),
            )
        )
        viewModel = newViewModel()

        viewModel.toggleTag("production")
        viewModel.updateSearchQuery("Chat")

        val results = viewModel.getFilteredAgents()
        assertEquals(1, results.size)
        assertEquals("a1", results.first().id.value)
    }

    @Test
    fun `getFilteredAgents returns all when no tags selected and no search`() = runTest {
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1", tags = listOf("alpha")),
                Agent(id = AgentId("a2"), name = "Agent2"),
            )
        )
        viewModel = newViewModel()

        assertEquals(2, viewModel.getFilteredAgents().size)
    }

    @Test
    fun `local runtime mode displays only local-bound agents`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("remote-agent"), name = "Remote"),
                Agent(id = AgentId("local-agent-generated"), name = "Local id"),
                Agent(
                    id = AgentId("metadata-local"),
                    name = "Local metadata",
                    metadata = mapOf(LocalAgentRuntimeMetadata.RuntimeKey to JsonPrimitive(LocalAgentRuntimeMetadata.LocalLettaCodeRuntime)),
                ),
            )
        )

        viewModel = newViewModel()

        assertEquals(listOf("local-agent-generated", "metadata-local"), viewModel.uiState.value.agents.map { it.id.value })
        assertEquals(listOf("local-agent-generated", "metadata-local"), viewModel.getFilteredAgents().map { it.id.value })
    }

    @Test
    fun `empty local runtime mode exposes create action`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(emptyList())

        viewModel = newViewModel()

        assertTrue(viewModel.uiState.value.agents.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(
            shouldShowEmptyAgentCreateAction(
                isShareMode = false,
                isHydrating = viewModel.uiState.value.isHydrating,
                searchQuery = viewModel.uiState.value.searchQuery,
            )
        )
    }

    @Test
    fun `empty local create action opens shared create dialog`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        viewModel = newViewModel()

        viewModel.showCreateDialog()

        assertTrue(viewModel.uiState.value.showCreateDialog)
    }

    @Test
    fun `empty create action hidden while searching or sharing`() = runTest {
        assertFalse(shouldShowEmptyAgentCreateAction(isShareMode = true, isHydrating = false, searchQuery = ""))
        assertFalse(shouldShowEmptyAgentCreateAction(isShareMode = false, isHydrating = false, searchQuery = "missing"))
        assertFalse(shouldShowEmptyAgentCreateAction(isShareMode = false, isHydrating = true, searchQuery = ""))
    }

    @Test
    fun `local runtime mode refreshes through the repository routing`() = runTest {
        // letta-mobile-y5c9u: the no-remote-fetch invariant moved into
        // AgentRepository (it lists from the on-device store in local mode),
        // so the ViewModel refreshes unconditionally — local agents must
        // repopulate after the per-session Room wipe.
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = localConfig()
        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        coEvery { agentRepository.refreshAgents() } returns Unit

        viewModel = newViewModel()
        viewModel.refresh()

        assertFalse(viewModel.uiState.value.isLoading)
        coVerify(exactly = 1) { agentRepository.refreshAgents() }
    }

    @Test
    fun `remote runtime mode displays all agents and refreshes normally`() = runTest {
        clearMocks(agentRepository, answers = false, recordedCalls = true)
        activeConfigFlow.value = remoteConfig()
        every { agentRepository.agents } returns MutableStateFlow(
            listOf(
                Agent(id = AgentId("remote-agent"), name = "Remote"),
                Agent(id = AgentId("local-agent-generated"), name = "Local id"),
            )
        )
        coEvery { agentRepository.refreshAgentsIfStale(any()) } returns false

        viewModel = newViewModel()

        assertEquals(listOf("remote-agent", "local-agent-generated"), viewModel.uiState.value.agents.map { it.id.value })
        coVerify(exactly = 1) { agentRepository.refreshAgentsIfStale(any()) }
    }

    @Test
    fun `loadAgents shows first hydrated page while refresh continues`() = runTest {
        val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
        val keepRefreshing = CompletableDeferred<Unit>()
        every { agentRepository.agents } returns agentsFlow
        coEvery { agentRepository.refreshAgentsIfStale(any()) } coAnswers {
            agentsFlow.value = listOf(Agent(id = AgentId("a1"), name = "Needle Agent"))
            keepRefreshing.await()
            true
        }

        viewModel = newViewModel()

        assertEquals(listOf("a1"), viewModel.uiState.value.agents.map { it.id.value })
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isHydrating)

        keepRefreshing.complete(Unit)
    }

    private fun newViewModel(): AgentListViewModel = AgentListViewModel(
        agentRepository = agentRepository,
        settingsRepository = settingsRepository,
        toolRepository = toolRepository,
        modelRepository = modelRepository,
        embeddedRuntimeStatusProvider = runtimeStatusProvider,
        embeddedModelRepository = embeddedModelRepository,
    )

    private fun localConfig(
        localModelHandle: String? = "google/gemma-test-litert-lm",
        localModelPath: String? = "/models/gemma-test.litertlm",
    ): LettaConfig = LettaConfig(
        id = "local-config",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://embedded",
        localModelHandle = localModelHandle,
        localModelPath = localModelPath,
        localModelRuntime = "litert-lm",
        localModelAccelerator = "gpu",
        localModelMaxTokens = 8192,
    )

    private fun remoteConfig(): LettaConfig = LettaConfig(
        id = "remote-config",
        mode = LettaConfig.Mode.CLOUD,
        serverUrl = "https://api.letta.com",
    )

    private fun downloadedModel(): EmbeddedModelCatalogItem = EmbeddedModelCatalogItem(
        entry = EmbeddedModelCatalogEntry(
            name = "Gemma test",
            modelId = "google/gemma-test-litert-lm",
            modelFile = "gemma-test.litertlm",
            sizeInBytes = 1024L,
            estimatedPeakMemoryInBytes = 2048L,
            defaultConfig = EmbeddedModelDefaultConfig(maxTokens = 8192, accelerators = listOf("GPU", "cpu")),
            taskTypes = listOf("chat"),
        ),
        state = EmbeddedModelDownloadState.Downloaded("/models/gemma-test.litertlm"),
    )

    private class FakeRuntimeStatusProvider(
        override val status: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
            nativeEnabled = true,
            assetsEnabled = true,
            version = "test",
            integrity = "test",
        ),
    ) : EmbeddedLettaCodeRuntimeStatusProvider

    private class FakeEmbeddedModelRepository : EmbeddedModelRepository {
        val catalogFlow = MutableStateFlow<List<EmbeddedModelCatalogItem>>(emptyList())
        override val catalog: StateFlow<List<EmbeddedModelCatalogItem>> = catalogFlow
        override suspend fun refresh() = Unit
        override suspend fun download(entry: EmbeddedModelCatalogEntry) = Unit
        override fun cancel(entry: EmbeddedModelCatalogEntry) = Unit
        override fun localPathFor(entry: EmbeddedModelCatalogEntry): String? = null
    }

    @Test
    fun `deleteAgent removes deleted agent from ui state immediately`() = runTest {
        val agentsFlow = MutableStateFlow(
            listOf(
                Agent(id = AgentId("a1"), name = "Agent1"),
                Agent(id = AgentId("a2"), name = "Agent2"),
            )
        )
        val favFlow = MutableStateFlow<String?>("a1")
        every { agentRepository.agents } returns agentsFlow
        every { settingsRepository.favoriteAgentId } returns favFlow
        every { settingsRepository.setFavoriteAgentId(any()) } answers { favFlow.value = firstArg() }
        coEvery { agentRepository.deleteAgent(AgentId("a1")) } answers {
            agentsFlow.value = agentsFlow.value.filterNot { it.id == AgentId("a1") }
        }
        viewModel = newViewModel()

        viewModel.deleteAgent(AgentId("a1"))

        assertEquals(listOf("a2"), viewModel.uiState.value.agents.map { it.id.value })
        assertEquals(null, viewModel.uiState.value.favoriteAgentId)
        coVerify(exactly = 1) { agentRepository.deleteAgent(AgentId("a1")) }
        io.mockk.verify(exactly = 1) { settingsRepository.setFavoriteAgentId(null) }
    }
}
