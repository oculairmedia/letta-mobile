package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationModelSettings
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConversationEffortGateway
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.ui.chat.session.ChatModelHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.85: the desktop composer's reasoning effort. Options carry the host
 * catalog's efforts where the gateway can send one, an effort pick reaches `model.update` with the
 * model and shows at once, the conversation's recorded effort shows after a reopen, and a gateway
 * without the effort neither offers nor silently drops one.
 */
private const val SOL = "openai/gpt-sol"
private const val MINIMAX = "lmstudio/minimax-m3"

private val chatModels = listOf(
    LlmModel(id = "sol", name = "Sol", handle = SOL),
    LlmModel(id = "minimax", name = "MiniMax", handle = MINIMAX),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatEffortTest {

    @Test
    fun effortPickReachesTheGatewayWithTheModelAndShowsAtOnce() = runTest {
        val gateway = EffortGateway(confirm = false)
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        controller.models.select(SOL, ReasoningEffortChoice.Named("high"))
        runCurrent()

        val change = gateway.changes.single()
        assertEquals("conv-1", change.conversationId.value)
        assertEquals(SOL, change.model.value)
        assertEquals(ReasoningEffortChoice.Named("high"), change.effort)
        // The host has not recorded it yet; the pick still shows.
        assertEquals("high", controller.models.selections.value.efforts["conv-1"])
        controller.close()
    }

    @Test
    fun theHostsRecordConfirmsThePick() = runTest {
        val gateway = EffortGateway(confirm = true)
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        controller.models.select(SOL, ReasoningEffortChoice.Named("low"))
        runCurrent()
        // A later effort-free re-pick of the same model keeps what the host recorded.
        controller.models.select(SOL)
        runCurrent()

        assertEquals("low", controller.models.selections.value.efforts["conv-1"])
        assertEquals(ReasoningEffortChoice.Unchanged, gateway.changes.last().effort)
        controller.close()
    }

    @Test
    fun aReopenedConversationShowsTheEffortTheHostRecords() = runTest {
        val gateway = EffortGateway(recorded = HostRecord(ModelHandle(SOL), ReasoningEffortChoice.Named("medium")))
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        assertEquals("medium", controller.models.selections.value.efforts["conv-1"])
        controller.close()
    }

    @Test
    fun aGatewayWithoutEffortsRefusesAnEffortPickAndRollsBack() = runTest {
        val gateway = modelOnlyGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        assertFalse(controller.models.supportsReasoningEffort)

        controller.models.select(SOL, ReasoningEffortChoice.Named("high"))
        runCurrent()

        assertTrue(gateway.modelUpdates.isEmpty())
        assertNotNull(controller.state.value.errorMessage)
        assertNull(controller.models.selections.value.efforts["conv-1"])
        controller.close()
    }

    @Test
    fun aGatewayWithoutEffortsStillSwitchesTheModel() = runTest {
        val gateway = modelOnlyGateway()
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        controller.models.select(SOL)
        runCurrent()

        assertEquals(listOf("conv-1" to SOL), gateway.modelUpdates)
        assertEquals(SOL, controller.state.value.composerModelLabel)
        controller.close()
    }

    @Test
    fun composerOffersTheCatalogsEffortsAndAppliesAPick() = runTest {
        val gateway = EffortGateway(confirm = true)
        val (controller, port) = startedPort(gateway)

        val before = port.composer.value.model
        assertNotNull(before)
        assertEquals(SOL, before.currentHandle)
        assertEquals(listOf("low", "medium", "high"), before.options.single { it.handle == SOL }.reasoningEfforts)
        assertTrue(before.options.single { it.handle == MINIMAX }.reasoningEfforts.isEmpty())
        assertNull(before.currentEffort)

        port.actions.selectModel(ChatModelHandle(SOL), ReasoningEffortChoice.Named("high"))
        runCurrent()

        assertEquals(ReasoningEffortChoice.Named("high"), gateway.changes.single().effort)
        assertEquals("high", port.composer.value.model?.currentEffort)
        controller.close()
    }

    @Test
    fun composerOffersNoEffortWhereTheGatewayCannotSendOne() = runTest {
        val (controller, port) = startedPort(modelOnlyGateway())

        val model = port.composer.value.model
        assertNotNull(model)
        assertTrue(model.options.all { it.reasoningEfforts.isEmpty() })
        controller.close()
    }

    @Test
    fun effortsAreKeyedBySelectionValueAndOffOnlyWhereUnsupported() {
        val catalog = listOf(CatalogModel(LlmModel(id = "sol", handle = SOL), exposed = true, reasoningEfforts = listOf("high")))
        val inputs = DesktopModelEffortInputs(
            models = chatModels,
            catalog = catalog,
            choices = ConversationModelChoices(efforts = mapOf("conv-1" to "high")),
            selectedConversationId = "conv-1",
            supported = true,
        )

        val efforts = desktopModelEfforts(inputs)
        assertEquals(mapOf(SOL to listOf("high")), efforts.byValue)
        assertEquals("high", efforts.current)
        assertEquals(DesktopModelEfforts(), desktopModelEfforts(inputs.copy(supported = false)))

        val ui = desktopChatModelUiState(SOL, listOf("Sol" to SOL, "MiniMax" to MINIMAX), efforts)
        assertEquals("high", ui.currentEffort)
        assertEquals(listOf("high"), ui.options.first().reasoningEfforts)
        assertTrue(ui.options.last().reasoningEfforts.isEmpty())
    }

    private fun TestScope.startedPort(gateway: DesktopChatGateway): Pair<DesktopChatController, DesktopChatSessionPort> {
        val controller = testController(gateway)
        val port = DesktopChatSessionPort(
            controller = controller,
            scope = backgroundScope,
            bindings = DesktopChatSessionBindings(modelCatalog = ModelCatalogRepository(catalogRpc)),
        )
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        controller.start()
        runCurrent()
        return controller to port
    }

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            agentByIdProvider = { ids -> ids.associateWith { Agent(id = AgentId(it), name = it, model = SOL) } },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )

    private companion object {
        /** The host's `model.list`: Sol takes three efforts, MiniMax none. */
        val catalogRpc = AdminRpcInvoker { method, _ ->
            check(method == "model.list") { "unexpected $method" }
            Json.parseToJsonElement(
                """
                [
                  {"id":"sol","name":"Sol","handle":"$SOL","provider_type":"openai","selection_handle":"$SOL",
                   "exposed":true,"reasoning_efforts":["low","medium","high"]},
                  {"id":"minimax","name":"MiniMax","handle":"$MINIMAX","provider_type":"lmstudio",
                   "selection_handle":"$MINIMAX","exposed":true}
                ]
                """.trimIndent(),
            )
        }
    }
}

/** What the host records for the conversation: its model and (when named) effort. */
private data class HostRecord(val model: ModelHandle, val effort: ReasoningEffortChoice.Named?)

private fun modelOnlyGateway() = ModelSwitchGateway(models = chatModels)

/** A `model.update` host: [confirm] records each change as the host's word, as the wrapper does. */
private class EffortGateway(
    private val confirm: Boolean = true,
    private var recorded: HostRecord? = null,
) : ModelSwitchGateway(models = chatModels), ConversationEffortGateway {
    val changes = mutableListOf<ConversationModelChange>()

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
        super.listConversations(limit, archiveStatus).map { conversation ->
            val record = recorded ?: return@map conversation
            conversation.copy(
                model = record.model.value,
                modelSettings = ConversationModelSettings(reasoningEffort = record.effort?.effort),
            )
        }

    override suspend fun updateConversationModel(change: ConversationModelChange): Conversation {
        changes += change
        if (confirm) recorded = HostRecord(change.model, change.effort.namedOr(recorded?.effort))
        return getConversation(change.conversationId.value)
    }

    private fun ReasoningEffortChoice.namedOr(previous: ReasoningEffortChoice.Named?): ReasoningEffortChoice.Named? = when (this) {
        ReasoningEffortChoice.Unchanged -> previous
        ReasoningEffortChoice.ProviderDefault -> null
        is ReasoningEffortChoice.Named -> this
    }
}
