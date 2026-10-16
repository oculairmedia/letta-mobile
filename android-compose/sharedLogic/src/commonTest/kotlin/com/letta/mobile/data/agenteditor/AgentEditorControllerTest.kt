package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.24: the shared agent editor's controller over a recording backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditorControllerTest {
    private val search = Tool(id = ToolId("t-search"), name = "web_search")
    private val linear = Tool(id = ToolId("t-linear"), name = "linear_issues", toolType = "external_mcp")
    private val ada = Agent(
        id = AgentId("a-1"),
        name = "Ada",
        description = "Analyst",
        model = "openai/gpt-5",
        system = "You are Ada.",
        tools = persistentListOf(search),
        tags = persistentListOf("team"),
        modelSettings = ModelSettings(providerType = "openai", reasoningEffort = "low"),
    )

    private class RecordingBackend(agent: Agent) : AgentEditorBackend {
        override val agents = MutableStateFlow(listOf(agent))
        override val models = MutableStateFlow(listOf(LlmModel(id = "m", handle = "openai/gpt-5", reasoningEffort = "low")))
        override val tools = MutableStateFlow<List<Tool>>(emptyList())
        override val modelsUnavailable = MutableStateFlow<String?>(null)
        val byId = mutableMapOf(agent.id.value to agent)
        val updates = mutableListOf<AgentUpdateParams>()
        val created = mutableListOf<AgentCreateParams>()
        val attached = mutableListOf<String>()
        val detached = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var failNextUpdate: String? = null

        var hangLoads = false
        override suspend fun load(agentId: AgentId): Agent {
            if (hangLoads) awaitCancellation()
            return byId[agentId.value] ?: error("No agent ${agentId.value}")
        }
        override suspend fun refreshCatalogs() = Unit
        override suspend fun create(params: AgentCreateParams): AgentCreated {
            created += params
            return AgentCreated("a-new", conversationId = "c-new")
        }
        override suspend fun update(agentId: AgentId, params: AgentUpdateParams) {
            failNextUpdate?.let { failNextUpdate = null; error(it) }
            updates += params
        }
        override suspend fun attachTool(agentId: AgentId, toolId: ToolId) { attached += toolId.value }
        override suspend fun detachTool(agentId: AgentId, toolId: ToolId) { detached += toolId.value }
        override suspend fun delete(agentId: AgentId) { deleted += agentId.value }
        override fun reasoningEffortsFor(handle: String) = listOf("low", "high")
    }

    private fun TestScope.editor(backend: RecordingBackend, mode: AgentEditorMode = AgentEditorMode.Edit("a-1")) =
        AgentEditorController(backend, backgroundScope, mode)

    /** letta-mobile-by4vm.121.11: the picker says why there are no models, not "no match". */
    @Test
    fun aFailedModelRefreshReasonReachesTheState() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend, AgentEditorMode.Create)
        assertNull(controller.state.value.modelsUnavailable)

        backend.modelsUnavailable.value = "On-device runtime stopped. Restart the app."

        assertEquals("On-device runtime stopped. Restart the app.", controller.state.value.modelsUnavailable)
        backend.modelsUnavailable.value = null
        assertNull(controller.state.value.modelsUnavailable)
    }

    @Test
    fun editLoadsTheAgentIntoTheFormAndNothingIsDirty() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada).apply { tools.value = listOf(search, linear) }
        val state = editor(backend).state.value

        assertFalse(state.loading)
        assertEquals("Ada", state.form.name)
        assertEquals("low", state.form.reasoningEffort)
        assertEquals(setOf("t-search"), state.form.toolIds)
        assertFalse(state.dirty)
        assertFalse(state.canSave)
        assertEquals(listOf("low", "high"), state.efforts)
        assertTrue(state.tools.single { it.id == "t-linear" }.mcp)
    }

    @Test
    fun aSaveWritesOnlyWhatChangedAndAttachesAndDetachesTools() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend)
        controller.update { copy(name = "Ada Lovelace", contextLimit = "64000", toolIds = setOf("t-linear")) }

        var saved: AgentCreated? = null
        controller.save { saved = it }

        assertEquals(AgentCreated("a-1"), saved)
        val patch = backend.updates.single()
        assertEquals("Ada Lovelace", patch.name)
        assertEquals(64_000, patch.contextWindowLimit)
        assertNull(patch.model)
        assertNull(patch.system)
        assertNull(patch.modelSettings)
        assertEquals(listOf("t-linear"), backend.attached)
        assertEquals(listOf("t-search"), backend.detached)
        assertFalse(controller.state.value.dirty)
    }

    @Test
    fun anEffortChangeCarriesTheProviderType() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend)
        controller.update { copy(reasoningEffort = "high") }
        controller.save {}

        val settings = backend.updates.single().modelSettings
        assertEquals("high", settings?.reasoningEffort)
        assertEquals("openai", settings?.providerType)
    }

    @Test
    fun anInvalidFormIsNotSaved() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend)
        controller.update { copy(name = " ", contextLimit = "lots") }

        val state = controller.state.value
        assertEquals(AgentEditorValidation.NAME_REQUIRED, state.problems[AgentEditorField.Name])
        assertEquals(AgentEditorValidation.CONTEXT_LIMIT_NUMBER, state.problems[AgentEditorField.ContextLimit])
        controller.save { error("must not save") }
        assertTrue(backend.updates.isEmpty())
    }

    @Test
    fun aFailedSaveIsReportedAndKeepsTheEdits() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada).apply { failNextUpdate = "offline" }
        val controller = editor(backend)
        controller.update { copy(description = "Mathematician") }
        controller.save { error("must not report success") }

        val state = controller.state.value
        assertEquals("offline", state.error)
        assertFalse(state.saving)
        assertTrue(state.dirty)
    }

    @Test
    fun aNewAgentFromAStarterCopiesItsSettingsAndKeepsTheTypedName() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend, AgentEditorMode.Create)
        controller.update { copy(name = "Grace", metadata = mapOf("letta_mobile.avatar_style" to JsonPrimitive("pill"))) }
        controller.pickStarter(AgentStarter.CopyOf("a-1", "Ada"))

        var created: AgentCreated? = null
        controller.save { created = it }

        assertEquals(AgentCreated("a-new", "c-new"), created)
        val params = backend.created.single()
        assertEquals("Grace", params.name)
        assertEquals("openai/gpt-5", params.model)
        assertEquals("You are Ada.", params.system)
        assertEquals(listOf("team"), params.tags?.toList())
        assertEquals(JsonPrimitive("pill"), params.metadata?.get("letta_mobile.avatar_style"))
        assertEquals(true, params.includeBaseTools)
    }

    @Test
    fun aBlankNewAgentNeedsOnlyAName() = runTest(UnconfinedTestDispatcher()) {
        val controller = editor(RecordingBackend(ada), AgentEditorMode.Create)
        assertFalse(controller.state.value.canSave)
        controller.update { copy(name = "Grace") }
        assertTrue(controller.state.value.canSave)
        assertEquals(listOf(AgentStarter.CopyOf("a-1", "Ada")), controller.state.value.starters)
    }

    @Test
    fun deleteRemovesTheAgent() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        var deleted = false
        editor(backend).delete { deleted = true }

        assertTrue(deleted)
        assertEquals(listOf("a-1"), backend.deleted)
    }

    @Test
    fun aMissingAgentShowsALoadErrorAndRetryLoadsAgain() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada)
        val controller = editor(backend, AgentEditorMode.Edit("a-2"))
        assertEquals("No agent a-2", controller.state.value.loadError)

        backend.byId["a-2"] = ada.copy(id = AgentId("a-2"), name = "Two")
        controller.retry()
        assertNull(controller.state.value.loadError)
        assertEquals("Two", controller.state.value.form.name)
    }

    @Test
    fun aLoadThatNeverAnswersEndsInAnErrorWithRetryNotAnEndlessSpinner() = runTest(UnconfinedTestDispatcher()) {
        val backend = RecordingBackend(ada).apply { hangLoads = true }
        val controller = editor(backend)
        assertTrue(controller.state.value.loading)

        advanceTimeBy(AgentEditorController.LOAD_TIMEOUT_MS + 1)

        val failed = controller.state.value
        assertFalse(failed.loading)
        assertEquals(AgentEditorController.LOAD_TIMED_OUT, failed.loadError)
        assertFalse(failed.canSave)

        backend.hangLoads = false
        controller.retry()
        assertNull(controller.state.value.loadError)
        assertEquals("Ada", controller.state.value.form.name)
    }

    @Test
    fun aLoadThatFailsEndsInAnError() = runTest(UnconfinedTestDispatcher()) {
        val state = editor(RecordingBackend(ada), AgentEditorMode.Edit("missing")).state.value
        assertFalse(state.loading)
        assertEquals("No agent missing", state.loadError)
    }

    @Test
    fun theModeComesFromTheAgentId() {
        assertEquals(AgentEditorMode.Create, AgentEditorMode.of(null))
        assertEquals(AgentEditorMode.Create, AgentEditorMode.of(""))
        assertEquals(AgentEditorMode.Edit("a"), AgentEditorMode.of("a"))
    }

    @Test
    fun providerTypesFallBackToTheHandlePrefix() {
        assertEquals("anthropic", ModelSettingsProviderTypes.normalize(null, "anthropic/claude"))
        assertEquals("openai", ModelSettingsProviderTypes.normalize("OpenAI", "x/y"))
        assertNull(ModelSettingsProviderTypes.normalize("letta", "lmstudio/qwen"))
    }
}
