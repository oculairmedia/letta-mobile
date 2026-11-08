package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelSettings
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.ui.chat.session.chatModelOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCreateAgentRoutingTest {
    @Test
    fun createAgentAppliesSharedCatalogRouting() = runTest {
        val gateway = RoutingGateway()
        val controller = routingController(gateway)
        startAndCreate(
            controller,
            AgentCreateParams(name = "Desktop agent", model = "openai/MiniMax-M3"),
        )

        assertEquals("llmux", gateway.createdParams?.modelSettings?.providerName)
        assertEquals(16_384, gateway.createdParams?.modelSettings?.maxOutputTokens)
        assertEquals("http://llmux:4000/v1", gateway.createdParams?.llmConfig?.modelEndpoint)
        assertEquals(200_000, gateway.createdParams?.llmConfig?.contextWindow)
        controller.close()
    }

    @Test
    fun createAgentRetainsSelectedRouteWhenHandlesCollide() = runTest {
        val routes = duplicateRoutingModels()
        val gateway = RoutingGateway(routes)
        val controller = routingController(gateway)
        val westSelection = chatModelOptions(routes).single { it.first.endsWith("West") }.second
        startAndCreate(
            controller,
            AgentCreateParams(name = "West agent", model = westSelection),
        )

        assertEquals("openai/gpt-4o", gateway.createdParams?.model)
        assertEquals("byok-west", gateway.createdParams?.modelSettings?.providerName)
        assertEquals("https://west.example/v1", gateway.createdParams?.llmConfig?.modelEndpoint)
        controller.close()
    }

    @Test
    fun createAgentResolvesSameAsCurrentRouteFromTemplateConfig() = runTest {
        val routes = duplicateRoutingModels()
        val gateway = RoutingGateway(routes)
        val template = Agent(
            id = AgentId("agent-0"),
            name = "Template",
            model = "openai/gpt-4o",
            modelSettings = ModelSettings(providerType = "azure", providerName = "byok-west"),
            llmConfig = LlmConfig(modelEndpoint = "https://west.example/v1"),
        )
        val controller = routingController(gateway, mapOf(template.id.value to template))

        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        backgroundScope.launch {
            controller.createAgentAndOpen(AgentCreateParams(name = "Cloned west agent", model = template.model))
        }
        runCurrent()

        assertEquals("openai/gpt-4o", gateway.createdParams?.model)
        assertEquals("byok-west", gateway.createdParams?.modelSettings?.providerName)
        assertEquals("https://west.example/v1", gateway.createdParams?.llmConfig?.modelEndpoint)
        controller.close()
    }

    @Test
    fun createAgentAwaitsPendingModelCatalog() = runTest {
        val catalogReady = CompletableDeferred<Unit>()
        val gateway = RoutingGateway(catalogReady = catalogReady)
        val controller = routingController(gateway)

        startAndCreate(
            controller,
            AgentCreateParams(name = "Pending catalog agent", model = "openai/MiniMax-M3"),
        )
        assertNull(gateway.createdParams)

        catalogReady.complete(Unit)
        runCurrent()

        assertEquals("llmux", gateway.createdParams?.modelSettings?.providerName)
        assertEquals(16_384, gateway.createdParams?.modelSettings?.maxOutputTokens)
        controller.close()
    }

    @Test
    fun creatingAnAgentDoesNotSelectAnUnrelatedListedConversation() = runTest {
        val gateway = RoutingGateway()
        val controller = routingController(gateway)
        controller.start()
        runCurrent()

        val created = controller.createAgentAndOpen(AgentCreateParams(name = "New agent"))
        runCurrent()

        assertEquals("created-conversation", created.conversationId)
        assertNull(controller.state.value.selectedConversationId)
        controller.close()
    }

    @Test
    fun aNewGatewayNeverReceivesCleanupForThePreviousGateway() = runTest {
        val first = RoutingGateway()
        val second = RoutingGateway()
        val gateways = ArrayDeque(listOf(first, second))
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            rosterProvider = TEST_ROSTER,
            gatewayFactory = { gateways.removeFirst() },
        )
        controller.start()
        runCurrent()
        controller.createConversationForAgent("agent-0")
        runCurrent()
        controller.retryConnection()
        runCurrent()

        controller.createConversationForAgent("agent-0")
        runCurrent()

        assertEquals(0, second.deleteCalls)
        assertEquals(1, second.createCalls)
        controller.close()
    }

    @Test
    fun oldUnsentCleanupCannotRemoveANewGatewayConversationWithTheSameId() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = RoutingGateway(deleteStarted = started, deleteRelease = release)
        val second = RoutingGateway()
        val gateways = ArrayDeque(listOf(first, second))
        val controller = DesktopChatController(defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER,
            gatewayFactory = { gateways.removeFirst() })
        controller.start()
        runCurrent()
        controller.createConversationForAgent("agent-0")
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        assertEquals(true, started.isCompleted)
        controller.retryConnection()
        runCurrent()
        controller.createConversationForAgent("agent-0")
        runCurrent()
        assertEquals(true, controller.state.value.conversations.any { it.id == "created-conversation" })

        release.complete(Unit)
        runCurrent()

        assertEquals(true, controller.state.value.conversations.any { it.id == "created-conversation" })
        controller.close()
    }

    @Test
    fun anOldCreateDoesNotRunAgainstANewGatewayAfterCleanupWaits() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val deleteRelease = CompletableDeferred<Unit>()
        val first = RoutingGateway(deleteStarted = deleteStarted, deleteRelease = deleteRelease)
        val second = RoutingGateway()
        val gateways = ArrayDeque(listOf(first, second))
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            rosterProvider = TEST_ROSTER,
            gatewayFactory = { gateways.removeFirst() },
        )
        controller.start()
        runCurrent()
        controller.createConversationForAgent("agent-0")
        runCurrent()
        assertEquals(1, first.createCalls)

        controller.createConversationForAgent("agent-0")
        runCurrent()
        assertEquals(true, deleteStarted.isCompleted)
        controller.retryConnection()
        runCurrent()
        deleteRelease.complete(Unit)
        runCurrent()

        assertEquals(0, second.createCalls)
        controller.close()
    }

    private fun TestScope.routingController(
        gateway: RoutingGateway,
        agentsById: Map<String, Agent> = emptyMap(),
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            rosterProvider = TEST_ROSTER,
            gatewayFactory = { gateway },
            agentByIdProvider = { ids -> agentsById.filterKeys(ids::contains) },
        )

    private fun TestScope.startAndCreate(
        controller: DesktopChatController,
        params: AgentCreateParams,
    ) {
        controller.start()
        runCurrent()
        backgroundScope.launch { controller.createAgentAndOpen(params) }
        runCurrent()
    }
}

private fun duplicateRoutingModels(): List<LlmModel> = listOf("east", "west").map { route ->
    LlmModel(
        id = route,
        name = "GPT-4o ${route.replaceFirstChar { it.uppercase() }}",
        handle = "openai/gpt-4o",
        providerType = "azure",
        providerName = "byok-$route",
        modelEndpoint = "https://$route.example/v1",
    )
}

private class RoutingGateway(
    private val models: List<LlmModel> = defaultRoutingModels(),
    private val catalogReady: CompletableDeferred<Unit>? = null,
    private val deleteStarted: CompletableDeferred<Unit>? = null,
    private val deleteRelease: CompletableDeferred<Unit>? = null,
) : FakeDesktopChatGateway(), ChatGatewayExtras {
    var createCalls = 0
        private set
    var deleteCalls = 0
        private set
    private var hasCreated = false
    var createdParams: AgentCreateParams? = null
        private set

    override suspend fun listLlmModels(): List<LlmModel> {
        catalogReady?.await()
        return models
    }

    companion object {
        private fun defaultRoutingModels(): List<LlmModel> = listOf(
            LlmModel(
                id = "openai/MiniMax-M3",
                name = "MiniMax-M3",
                handle = "openai/MiniMax-M3",
                providerType = "openai",
                providerName = "llmux",
                modelEndpoint = "http://llmux:4000/v1",
                contextWindow = 200_000,
                maxOutputTokens = 16_384,
            ),
        )
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        createdParams = params
        return Agent(id = AgentId("created-agent"), name = params.name.orEmpty())
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        require(agentId in setOf("agent-0", "created-agent"))
        createCalls++
        hasCreated = true
        return Conversation(id = ConversationId("created-conversation"), agentId = AgentId(agentId))
    }

    override suspend fun deleteConversation(conversationId: String) {
        deleteCalls++
        require(conversationId == "created-conversation" && hasCreated)
        deleteStarted?.complete(Unit)
        deleteRelease?.await()
        hasCreated = false
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        error("unused")

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        error("unused")
}
