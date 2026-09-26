package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * letta-mobile-okvyf: the model picker's selected row and the composer model chip both render
 * `composerModelLabel`. A switch must move it, keep it across re-selection, never be clobbered by
 * a slower agent lookup, and roll back when the server refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerModelSwitchTest {

    @Test
    fun switchingModelUpdatesTheLabelAndReachesTheServer() = runTest {
        val gateway = ModelSwitchGateway()
        val controller = testController(gateway, agentModel = "anthropic/old")
        controller.start()
        runCurrent()
        assertEquals("anthropic/old", controller.state.value.composerModelLabel)

        controller.setConversationModel("openai/new")
        runCurrent()

        assertEquals("openai/new", controller.state.value.composerModelLabel)
        assertEquals(listOf("conv-1" to "openai/new"), gateway.modelUpdates)
        controller.close()
    }

    @Test
    fun switchSurvivesReselectingTheConversation() = runTest {
        val gateway = ModelSwitchGateway(conversationIds = listOf("conv-1", "conv-2"))
        val controller = testController(gateway, agentModel = "anthropic/old")
        controller.start()
        runCurrent()

        controller.setConversationModel("openai/new")
        runCurrent()
        controller.selectConversation("conv-2")
        runCurrent()
        assertEquals("anthropic/old", controller.state.value.composerModelLabel)
        controller.selectConversation("conv-1")
        runCurrent()

        assertEquals("openai/new", controller.state.value.composerModelLabel)
        controller.close()
    }

    @Test
    fun pickMadeWhileTheAgentLookupIsInFlightIsNotOverwrittenByTheAgentModel() = runTest {
        val agentLookup = CompletableDeferred<Unit>()
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { ModelSwitchGateway() },
            agentByIdProvider = { ids ->
                agentLookup.await()
                ids.associateWith { Agent(id = AgentId(it), name = it, model = "anthropic/old") }
            },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
        controller.start()
        runCurrent()

        controller.setConversationModel("openai/new")
        runCurrent()
        agentLookup.complete(Unit)
        runCurrent()

        assertEquals("openai/new", controller.state.value.composerModelLabel)
        controller.close()
    }

    @Test
    fun rejectedSwitchRollsTheLabelBack() = runTest {
        val gateway = ModelSwitchGateway(failModelUpdate = true)
        val controller = testController(gateway, agentModel = "anthropic/old")
        controller.start()
        runCurrent()

        controller.setConversationModel("openai/new")
        runCurrent()

        val state = controller.state.value
        assertEquals("anthropic/old", state.composerModelLabel)
        assertEquals("model rejected", state.errorMessage)
        controller.selectConversation("conv-1")
        runCurrent()
        assertEquals("anthropic/old", controller.state.value.composerModelLabel)
        controller.close()
    }

    @Test
    fun backendWithoutModelControlDoesNotPretendToSwitch() = runTest {
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { FakeDesktopChatGateway() },
            agentByIdProvider = { ids -> ids.associateWith { Agent(id = AgentId(it), name = it, model = "anthropic/old") } },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
        controller.start()
        runCurrent()

        controller.setConversationModel("openai/new")
        runCurrent()

        assertEquals("anthropic/old", controller.state.value.composerModelLabel)
        assertNotNull(controller.state.value.errorMessage)
        controller.close()
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        agentModel: String,
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            agentByIdProvider = { ids -> ids.associateWith { Agent(id = AgentId(it), name = it, model = agentModel) } },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
}

private class ModelSwitchGateway(
    conversationIds: List<String> = listOf("conv-1"),
    private val failModelUpdate: Boolean = false,
) : FakeDesktopChatGateway(conversationIds = conversationIds), ChatGatewayExtras {
    val modelUpdates = mutableListOf<Pair<String, String>>()

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        error("not used by this test")

    override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used by this test")

    override suspend fun listLlmModels(): List<LlmModel> = emptyList()

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation {
        if (failModelUpdate) error("model rejected")
        modelUpdates += conversationId to model
        return getConversation(conversationId)
    }

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        error("not used by this test")
}
