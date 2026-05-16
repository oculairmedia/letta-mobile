package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.AgentId
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentEnvironmentVariable
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.CompactionSettings
import com.letta.mobile.data.model.ModelSettings
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
import com.letta.mobile.bot.connection.ClientModeConnectionState
import com.letta.mobile.bot.connection.ClientModeConnectionTester
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `loadAgent includes primary model advanced settings`() = runTest {
        fakeAgentRepo.loadedModelSettings = ModelSettings(
            providerType = "openai",
            providerName = "production-openai",
            providerCategory = "byok",
            temperature = 0.7,
            maxOutputTokens = 8192,
            parallelToolCalls = true,
            enableReasoner = true,
            reasoningEffort = "high",
            maxReasoningTokens = 2048,
            reasoning = buildJsonObject { put("reasoning_effort", JsonPrimitive("medium")) },
            frequencyPenalty = 0.25,
            verbosity = "medium",
            strict = true,
            responseFormat = buildJsonObject { put("type", JsonPrimitive("json_object")) },
            responseSchema = buildJsonObject { put("type", JsonPrimitive("json_schema")) },
            thinkingConfig = buildJsonObject { put("thinking_budget", JsonPrimitive(1024)) },
            putInnerThoughtsInKwargs = true,
            toolCallParser = "hermes",
            effort = "max",
        )

        viewModel.loadAgent()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("production-openai", state.data.modelProviderName)
            assertEquals("byok", state.data.modelProviderCategory)
            assertTrue(state.data.modelEnableReasoner)
            assertEquals("high", state.data.modelReasoningEffort)
            assertEquals("2048", state.data.modelMaxReasoningTokens)
            assertTrue(state.data.modelReasoningJson.contains("reasoning_effort"))
            assertEquals("0.25", state.data.modelFrequencyPenalty)
            assertEquals("medium", state.data.modelVerbosity)
            assertTrue(state.data.modelStrictToolCalling)
            assertTrue(state.data.modelResponseFormatJson.contains("json_object"))
            assertTrue(state.data.modelResponseSchemaJson.contains("json_schema"))
            assertTrue(state.data.modelThinkingConfigJson.contains("thinking_budget"))
            assertTrue(state.data.modelPutInnerThoughtsInKwargs)
            assertEquals("hermes", state.data.modelToolCallParser)
            assertEquals("max", state.data.modelAnthropicEffort)
        }
    }

    @Test
    fun `saveAgent persists primary model advanced settings`() = runTest {
        viewModel.loadAgent()
        viewModel.updateModelProviderName("production-openai")
        viewModel.updateModelProviderCategory("byok")
        viewModel.updateModelEnableReasoner(true)
        viewModel.updateModelReasoningEffort("xhigh")
        viewModel.updateModelMaxReasoningTokens("4096")
        viewModel.updateModelReasoningJson("""{"reasoning_effort":"high"}""")
        viewModel.updateModelFrequencyPenalty("0.4")
        viewModel.updateModelVerbosity("high")
        viewModel.updateModelStrictToolCalling(true)
        viewModel.updateModelResponseFormatJson("""{"type":"json_object"}""")
        viewModel.updateModelResponseSchemaJson("""{"type":"json_schema"}""")
        viewModel.updateModelThinkingConfigJson("""{"thinking_budget":2048}""")
        viewModel.updateModelPutInnerThoughtsInKwargs(true)
        viewModel.updateModelToolCallParser("hermes")
        viewModel.updateModelAnthropicEffort("max")

        viewModel.saveAgent {}

        val settings = fakeAgentRepo.lastUpdateParams?.modelSettings
        assertEquals("production-openai", settings?.providerName)
        assertEquals("byok", settings?.providerCategory)
        assertEquals(true, settings?.enableReasoner)
        assertEquals("xhigh", settings?.reasoningEffort)
        assertEquals(4096, settings?.maxReasoningTokens)
        assertEquals("""{"reasoning_effort":"high"}""", settings?.reasoning.toString())
        assertEquals(0.4, settings?.frequencyPenalty ?: -1.0, 0.001)
        assertEquals("high", settings?.verbosity)
        assertEquals(true, settings?.strict)
        assertEquals("""{"type":"json_object"}""", settings?.responseFormat.toString())
        assertEquals("""{"type":"json_schema"}""", settings?.responseSchema.toString())
        assertEquals("""{"thinking_budget":2048}""", settings?.thinkingConfig.toString())
        assertEquals(true, settings?.putInnerThoughtsInKwargs)
        assertEquals("hermes", settings?.toolCallParser)
        assertEquals("max", settings?.effort)
    }

    @Test
    fun `saveAgent rejects invalid primary model json settings`() = runTest {
        viewModel.loadAgent()
        viewModel.updateModelResponseFormatJson("not json")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
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
    fun `loadAgent includes compaction settings`() = runTest {
        fakeAgentRepo.loadedCompactionSettings = CompactionSettings(
            prompt = "Summarize for handoff",
            clipChars = 12000,
            slidingWindowPercentage = 0.45,
            promptAcknowledgement = true,
            mode = "self_compact_sliding_window",
            model = "openai/gpt-5-mini",
            modelSettings = buildJsonObject {
                put("max_output_tokens", JsonPrimitive(1024))
            },
        )

        viewModel.loadAgent()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("Summarize for handoff", state.data.summarizationPrompt)
            assertEquals(12000, state.data.compactionClipChars)
            assertEquals(0.45f, state.data.slidingWindowPercentage, 0.001f)
            assertTrue(state.data.promptAcknowledgement)
            assertEquals("self_compact_sliding_window", state.data.compactionMode)
            assertEquals("openai/gpt-5-mini", state.data.compactionModel)
            assertTrue(state.data.compactionModelSettingsJson.contains("max_output_tokens"))
        }
    }

    @Test
    fun `saveAgent persists compaction settings`() = runTest {
        fakeAgentRepo.loadedCompactionSettings = CompactionSettings(
            model = "openai/gpt-5-mini",
            mode = "sliding_window",
        )

        viewModel.loadAgent()
        viewModel.updateCompactionMode("self_compact_all")
        viewModel.updateCompactionModel("anthropic/claude-3-5-sonnet")
        viewModel.updateCompactionModelSettingsJson("""{"max_output_tokens":1024}""")
        viewModel.updateSummarizationPrompt("Keep tasks, decisions, and open questions.")
        viewModel.updateCompactionClipChars(24000)
        viewModel.updateSlidingWindowPercentage(0.35f)
        viewModel.updatePromptAcknowledgement(true)

        viewModel.saveAgent {}

        val compactionSettings = fakeAgentRepo.lastUpdateParams?.compactionSettings
        assertEquals("anthropic/claude-3-5-sonnet", compactionSettings?.model)
        assertEquals("self_compact_all", compactionSettings?.mode)
        assertEquals("""{"max_output_tokens":1024}""", compactionSettings?.modelSettings.toString())
        assertEquals("Keep tasks, decisions, and open questions.", compactionSettings?.prompt)
        assertEquals(24000, compactionSettings?.clipChars)
        assertEquals(0.35, compactionSettings?.slidingWindowPercentage ?: -1.0, 0.001)
        assertEquals(true, compactionSettings?.promptAcknowledgement)
    }

    @Test
    fun `saveAgent rejects invalid compaction model settings json`() = runTest {
        viewModel.loadAgent()
        viewModel.updateCompactionModelSettingsJson("not json")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    @Test
    fun `loadAgent includes tool rules json`() = runTest {
        fakeAgentRepo.loadedToolRules = listOf(
            buildJsonObject {
                put("type", JsonPrimitive("requires_approval"))
                put("tool_name", JsonPrimitive("shell"))
            }
        )

        viewModel.loadAgent()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertTrue(state.data.toolRulesJson.contains("requires_approval"))
            assertTrue(state.data.toolRulesJson.contains("shell"))
        }
    }

    @Test
    fun `loadAgent includes secrets and tool environment variables`() = runTest {
        fakeAgentRepo.loadedSecrets = listOf(
            AgentEnvironmentVariable(key = "VISIBLE_SECRET", value = "visible-value"),
            AgentEnvironmentVariable(id = "secret-2", key = "HIDDEN_SECRET", valueEnc = "encrypted"),
        )
        fakeAgentRepo.loadedToolEnvironmentVariables = listOf(
            AgentEnvironmentVariable(key = "TOOL_HOME", value = "/tools"),
        )

        viewModel.loadAgent()

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals("VISIBLE_SECRET", state.data.agentSecrets[0].key)
            assertEquals("visible-value", state.data.agentSecrets[0].value)
            assertTrue(state.data.agentSecrets[0].hasStoredValue)
            assertEquals("HIDDEN_SECRET", state.data.agentSecrets[1].key)
            assertEquals("", state.data.agentSecrets[1].value)
            assertTrue(state.data.agentSecrets[1].hasStoredValue)
            assertEquals("TOOL_HOME", state.data.toolEnvironmentVariables[0].key)
            assertEquals("/tools", state.data.toolEnvironmentVariables[0].value)
        }
    }

    @Test
    fun `saveAgent persists valid tool rules and preserves attached tools`() = runTest {
        viewModel.loadAgent()
        viewModel.updateToolRulesJson("""[{"type":"requires_approval","tool_name":"shell"}]""")

        viewModel.saveAgent {}

        val toolRules = fakeAgentRepo.lastUpdateParams?.toolRules
        assertEquals(1, toolRules?.size)
        assertEquals("requires_approval", toolRules?.first()?.get("type")?.jsonPrimitive?.content)
        assertEquals("shell", toolRules?.first()?.get("tool_name")?.jsonPrimitive?.content)
        assertEquals(null, fakeAgentRepo.lastUpdateParams?.toolIds)
    }

    @Test
    fun `saveAgent rejects tool rules json object`() = runTest {
        viewModel.loadAgent()
        viewModel.updateToolRulesJson("""{"type":"requires_approval"}""")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    @Test
    fun `saveAgent rejects tool rules array with non-object item`() = runTest {
        viewModel.loadAgent()
        viewModel.updateToolRulesJson("""["requires_approval"]""")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            assertTrue(awaitItem() is UiState.Error)
        }
    }

    @Test
    fun `saveAgent persists edited secrets and tool environment variables`() = runTest {
        fakeAgentRepo.loadedSecrets = listOf(
            AgentEnvironmentVariable(key = "EXISTING_SECRET", value = "old-secret"),
        )
        fakeAgentRepo.loadedToolEnvironmentVariables = listOf(
            AgentEnvironmentVariable(key = "TOOL_HOME", value = "/tools"),
        )

        viewModel.loadAgent()
        viewModel.updateAgentSecretValue(0, "new-secret")
        viewModel.addAgentSecret()
        viewModel.updateAgentSecretKey(1, "NEW_SECRET")
        viewModel.updateAgentSecretValue(1, "created-secret")
        viewModel.updateToolEnvironmentVariableValue(0, "/usr/local/tools")
        viewModel.addToolEnvironmentVariable()
        viewModel.updateToolEnvironmentVariableKey(1, "DEBUG_TOOLS")
        viewModel.updateToolEnvironmentVariableValue(1, "true")

        viewModel.saveAgent {}

        assertEquals(
            mapOf(
                "EXISTING_SECRET" to "new-secret",
                "NEW_SECRET" to "created-secret",
            ),
            fakeAgentRepo.lastUpdateParams?.secrets,
        )
        assertEquals(
            mapOf(
                "TOOL_HOME" to "/usr/local/tools",
                "DEBUG_TOOLS" to "true",
            ),
            fakeAgentRepo.lastUpdateParams?.toolExecEnvironmentVariables,
        )
    }

    @Test
    fun `saveAgent omits unchanged hidden secrets and tool environment variables`() = runTest {
        fakeAgentRepo.loadedSecrets = listOf(
            AgentEnvironmentVariable(id = "secret-1", key = "HIDDEN_SECRET", valueEnc = "encrypted"),
        )
        fakeAgentRepo.loadedToolEnvironmentVariables = listOf(
            AgentEnvironmentVariable(id = "tool-env-1", key = "HIDDEN_TOOL_ENV", valueEnc = "encrypted"),
        )

        viewModel.loadAgent()
        viewModel.saveAgent {}

        assertNull(fakeAgentRepo.lastUpdateParams?.secrets)
        assertNull(fakeAgentRepo.lastUpdateParams?.toolExecEnvironmentVariables)
    }

    @Test
    fun `saveAgent rejects section changes that would overwrite hidden secrets`() = runTest {
        fakeAgentRepo.loadedSecrets = listOf(
            AgentEnvironmentVariable(id = "secret-1", key = "HIDDEN_SECRET", valueEnc = "encrypted"),
        )

        viewModel.loadAgent()
        viewModel.addAgentSecret()
        viewModel.updateAgentSecretKey(1, "NEW_SECRET")
        viewModel.updateAgentSecretValue(1, "created-secret")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Error
            assertTrue(state.message.contains("hidden existing values"))
        }
        assertNull(fakeAgentRepo.lastUpdateParams)
    }

    @Test
    fun `saveAgent rejects duplicate secret keys`() = runTest {
        viewModel.loadAgent()
        viewModel.addAgentSecret()
        viewModel.updateAgentSecretKey(0, "DUPLICATE_KEY")
        viewModel.updateAgentSecretValue(0, "first")
        viewModel.addAgentSecret()
        viewModel.updateAgentSecretKey(1, "DUPLICATE_KEY")
        viewModel.updateAgentSecretValue(1, "second")

        viewModel.saveAgent {}

        viewModel.uiState.test {
            val state = awaitItem() as UiState.Error
            assertTrue(state.message.contains("duplicate key"))
        }
        assertNull(fakeAgentRepo.lastUpdateParams)
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
        var loadedCompactionSettings: CompactionSettings? = null
        var loadedModelSettings: ModelSettings? = null
        var loadedToolRules: List<kotlinx.serialization.json.JsonObject> = emptyList()
        var loadedSecrets: List<AgentEnvironmentVariable> = emptyList()
        var loadedToolEnvironmentVariables: List<AgentEnvironmentVariable> = emptyList()

        override fun getAgent(id: String): Flow<Agent> = flow {
            if (shouldFail) throw Exception("Load failed")
            emit(Agent(
                id = AgentId("a1"),
                name = "Test Agent",
                description = "A test agent",
                model = loadedModel,
                embedding = "openai/text-embedding-3-small",
                tags = listOf("test"),
                system = "System prompt",
                agentType = "stateful",
                enableSleeptime = true,
                compactionSettings = loadedCompactionSettings,
                toolRules = loadedToolRules,
                secrets = loadedSecrets,
                toolExecEnvironmentVariables = loadedToolEnvironmentVariables,
                tools = listOf(TestData.tool(id = "t1", name = "attached_tool")),
                modelSettings = loadedModelSettings ?: ModelSettings(
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
                id = AgentId(id),
                name = params.name ?: "Test Agent",
                description = params.description,
                model = params.model,
                embedding = params.embedding,
                system = params.system,
                tags = params.tags ?: emptyList(),
                enableSleeptime = params.enableSleeptime,
                compactionSettings = params.compactionSettings,
                secrets = params.secrets?.map { (key, value) ->
                    AgentEnvironmentVariable(key = key, value = value)
                }.orEmpty(),
                toolExecEnvironmentVariables = params.toolExecEnvironmentVariables?.map { (key, value) ->
                    AgentEnvironmentVariable(key = key, value = value)
                }.orEmpty(),
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
                id = BlockId("updated-block"),
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
                id = BlockId(blockId),
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
