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
import com.letta.mobile.desktop.buildModelOptions
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        startAndCreate(controller, "Desktop agent", "openai/MiniMax-M3")

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
        val westSelection = buildModelOptions(routes).single { it.first.endsWith("West") }.second
        startAndCreate(controller, "West agent", westSelection)

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

        startAndCreate(controller, "Cloned west agent", template.model)

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

        startAndCreate(controller, "Pending catalog agent", "openai/MiniMax-M3")
        assertNull(gateway.createdParams)

        catalogReady.complete(Unit)
        runCurrent()

        assertEquals("llmux", gateway.createdParams?.modelSettings?.providerName)
        assertEquals(16_384, gateway.createdParams?.modelSettings?.maxOutputTokens)
        controller.close()
    }

    private fun TestScope.routingController(
        gateway: RoutingGateway,
        agentsById: Map<String, Agent> = emptyMap(),
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            agentByIdProvider = { ids -> agentsById.filterKeys(ids::contains) },
        )

    private fun TestScope.startAndCreate(
        controller: DesktopChatController,
        name: String,
        model: String?,
    ) {
        controller.start()
        runCurrent()
        controller.createAgent(name = name, model = model, embedding = null)
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
) : FakeDesktopChatGateway(), ChatGatewayExtras {
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

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        Conversation(id = ConversationId("created-conversation"), agentId = AgentId(agentId))

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        error("unused")

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        error("unused")
}
