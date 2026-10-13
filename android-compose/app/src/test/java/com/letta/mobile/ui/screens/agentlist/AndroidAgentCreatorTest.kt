package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LocalAgentRuntimeMetadata
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDefaultConfig
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/** letta-mobile-ckmub.3.24: how the shared agent editor creates an agent on Android (moved from the agent list). */
@Tag("unit")
class AndroidAgentCreatorTest {
    private val agentRepository: IAgentRepository = mockk(relaxed = true)
    private val settingsRepository: ISettingsRepository = mockk(relaxed = true)
    private val activeConfig = MutableStateFlow<LettaConfig?>(null)
    private val catalog = FakeEmbeddedModelRepository()
    private val creator = AndroidAgentCreator(agentRepository, settingsRepository, FakeRuntimeStatusProvider(), catalog)

    @Before
    fun setup() {
        every { settingsRepository.activeConfig } returns activeConfig
    }

    @Test
    fun `a remote create forwards the request unchanged and refreshes the roster`() = runTest {
        activeConfig.value = remoteConfig()
        val params = AgentCreateParams(name = "Remote", model = "openai/gpt-4o", toolIds = persistentListOf(ToolId("t1")), includeBaseTools = true)
        val captured = slot<AgentCreateParams>()
        coEvery { agentRepository.createAgent(capture(captured)) } returns Agent(id = AgentId("a-remote"), name = "Remote")

        assertEquals(AgentCreated("a-remote"), creator.create(params))
        assertEquals(params, captured.captured)
        coVerify(exactly = 1) { agentRepository.refreshAgents() }
        coVerify(exactly = 0) { agentRepository.createLocalAgent(any()) }
    }

    @Test
    fun `a local create stamps the runtime and turns tools off`() = runTest {
        activeConfig.value = localConfig()
        catalog.catalogFlow.value = listOf(downloadedModel())
        val captured = slot<AgentCreateParams>()
        coEvery { agentRepository.createLocalAgent(capture(captured)) } returns Agent(id = AgentId("local-agent-test"), name = "Local")

        val created = creator.create(
            AgentCreateParams(name = "Local", toolIds = persistentListOf(ToolId("t1")), includeBaseTools = true, enableSleeptime = true),
        )

        assertEquals(AgentCreated("local-agent-test", conversationId = "local-conv-local-agent-test"), created)
        val params = captured.captured
        assertEquals("google/gemma-test-litert-lm", params.model)
        assertEquals(LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME, params.modelSettings?.providerType)
        assertNull(params.toolIds)
        assertEquals(false, params.includeBaseTools)
        assertEquals(false, params.enableSleeptime)
        assertEquals(
            "${LocalAgentRuntimeMetadata.LOCAL_LETTA_CODE_RUNTIME}:local-config",
            params.metadata?.get(LocalAgentRuntimeMetadata.RUNTIME_ID_KEY)?.jsonPrimitive?.contentOrNull,
        )
        coVerify(exactly = 0) { agentRepository.createAgent(any()) }
    }

    // letta-mobile-3icw7: a model picked in the editor wins over the config-level default.
    @Test
    fun `a local create keeps the picked model`() = runTest {
        activeConfig.value = localConfig()
        catalog.catalogFlow.value = listOf(downloadedModel())
        val captured = slot<AgentCreateParams>()
        coEvery { agentRepository.createLocalAgent(capture(captured)) } returns Agent(id = AgentId("l"), name = "Local")

        creator.create(AgentCreateParams(name = "Local", model = "MiniMax-M3"))

        assertEquals("MiniMax-M3", captured.captured.model)
        assertEquals("MiniMax-M3", captured.captured.metadata?.get(LocalAgentRuntimeMetadata.LOCAL_MODEL_HANDLE_KEY)?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a local create without a model says what to set up and creates nothing`() = runTest {
        activeConfig.value = localConfig(localModelHandle = "local/default", localModelPath = null)

        val failure = runCatching { creator.create(AgentCreateParams(name = "Local")) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Download or import a model in Settings before creating a local agent.", failure?.message)
        coVerify(exactly = 0) { agentRepository.createLocalAgent(any()) }
        coVerify(exactly = 0) { agentRepository.createAgent(any()) }
    }

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

    private fun remoteConfig(): LettaConfig = LettaConfig(id = "remote-config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://api.letta.com")

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
}
