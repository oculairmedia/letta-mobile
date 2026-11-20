package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The agent editor's voice description, saved into the agent's `voice` block at the memory write boundary. */
class AgentEditorVoiceSaveTest {
    private val ada = Agent(id = AgentId("a-1"), name = "Ada", model = "openai/gpt-5")

    /**
     * A memory store behind the editor: [agent] is the block as it stands and each write changes it;
     * [load] serves it, or [staleLoad] when set (a read that has not caught up with a write yet).
     */
    private class MemoryBackend(var agent: Agent) : AgentEditorBackend {
        var staleLoad: Agent? = null
        override val agents = MutableStateFlow(listOf(agent))
        override val models = MutableStateFlow(listOf(LlmModel(id = "m", handle = "openai/gpt-5")))
        override val tools = MutableStateFlow<List<Tool>>(emptyList())
        override val modelsUnavailable = MutableStateFlow<String?>(null)

        /** Each voice block write as it reached the memory write boundary: the value and whether it updated an existing block. */
        val voiceWrites = mutableListOf<Pair<String, Boolean>>()

        override suspend fun load(agentId: AgentId): Agent = staleLoad ?: agent
        override suspend fun refreshCatalogs() = Unit
        override suspend fun create(params: AgentCreateParams) = AgentCreated("a-new")
        override suspend fun update(agentId: AgentId, params: AgentUpdateParams) = Unit
        override suspend fun attachTool(agentId: AgentId, toolId: ToolId) = Unit
        override suspend fun detachTool(agentId: AgentId, toolId: ToolId) = Unit
        override suspend fun delete(agentId: AgentId) = Unit

        override suspend fun writeVoiceCore(agentId: AgentId, value: String, exists: Boolean) {
            val hasBlock = AgentEditorParams.voiceBlockOf(agent) != null
            check(exists == hasBlock) { if (exists) "no voice block to write" else "the voice block already exists" }
            voiceWrites += value to exists
            agent = agent.withVoice(value)
        }
    }

    private fun TestScope.editor(backend: MemoryBackend) = AgentEditorController(backend, backgroundScope, AgentEditorMode.Edit("a-1"))

    @Test
    fun aSaveKeepsFrontMatterChangedAfterTheEditorLoaded() = runTest(UnconfinedTestDispatcher()) {
        val backend = MemoryBackend(ada.withVoice("---\nlimit: 200\n---\nA calm voice."))
        val controller = editor(backend)
        backend.agent = ada.withVoice("---\nlimit: 400\ndescription: How Ada sounds\n---\nA calm voice.")

        controller.update { copy(voiceDescription = "A warm voice.") }
        controller.save {}

        assertEquals(null, controller.state.value.error)
        assertEquals(listOf("---\nlimit: 400\ndescription: How Ada sounds\n---\nA warm voice." to true), backend.voiceWrites)
    }

    @Test
    fun aBlockCreatedBySaveIsWrittenOnTheNextSave() = runTest(UnconfinedTestDispatcher()) {
        val backend = MemoryBackend(ada).apply { staleLoad = ada }
        val controller = editor(backend)

        controller.update { copy(voiceDescription = "A warm voice.") }
        controller.save {}
        controller.update { copy(voiceDescription = "A bright voice.") }
        controller.save {}

        assertEquals(null, controller.state.value.error)
        assertEquals(listOf("A warm voice." to false, "A bright voice." to true), backend.voiceWrites)
    }
}

private fun Agent.withVoice(value: String) = copy(blocks = persistentListOf(Block(id = BlockId("b-voice"), label = "voice", value = value)))
