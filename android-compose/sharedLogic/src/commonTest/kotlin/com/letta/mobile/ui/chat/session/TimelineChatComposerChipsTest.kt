package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.EnginePermissionModes
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.17.2: the composer chips a one-conversation page (the web's) gets from the
 * shared [ConversationModels] and [GatewayPermissionModes], the sources desktop's controller binds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineChatComposerChipsTest {
    private val target = TimelineChatTarget(agentId = "agent-1", agentName = "Ada", conversationId = "conv-1")

    @Test
    fun theChipShowsTheConversationsRecordedModelAndTheBackendsOptions() = runTest {
        val backend = FakeModelBackend(recordedModel = SOL)
        val models = chips(backend).models

        runCurrent()

        val chip = assertNotNull(models?.model?.value)
        assertEquals(SOL, chip.currentHandle)
        assertEquals(listOf(SOL, MINIMAX), chip.options.map { it.handle })
    }

    @Test
    fun withoutARecordedModelTheChipShowsTheAgentsModel() = runTest {
        val models = chips(FakeModelBackend(recordedModel = null), agentModel = MINIMAX).models

        runCurrent()

        assertEquals(MINIMAX, models?.model?.value?.currentHandle)
    }

    @Test
    fun aPickSwitchesTheConversationThroughTheBackendAndShows() = runTest {
        val backend = FakeModelBackend(recordedModel = SOL)
        val models = assertNotNull(chips(backend).models)
        runCurrent()

        models.selectModel(ChatModelHandle(MINIMAX), ReasoningEffortChoice.Unchanged)
        runCurrent()

        assertEquals(ConversationModelChange(ConversationId("conv-1"), ModelHandle(MINIMAX)), backend.switches.single())
        assertEquals(MINIMAX, models.model.value?.currentHandle)
    }

    @Test
    fun aRefusedSwitchPutsThePreviousModelBack() = runTest {
        val backend = FakeModelBackend(recordedModel = SOL, refuse = true)
        val models = assertNotNull(chips(backend).models)
        runCurrent()

        models.selectModel(ChatModelHandle(MINIMAX), ReasoningEffortChoice.Unchanged)
        runCurrent()

        assertEquals(SOL, models.model.value?.currentHandle)
    }

    @Test
    fun theModeChipFollowsTheSessionsRegistryAndIsOffWithoutOne() = runTest {
        val registry = PermissionModeRegistry(MutableStateFlow(AppServerPermissionMode.Unrestricted))
        val governed = chips(FakeModelBackend(recordedModel = SOL), permissions = EnginePermissionModes(engine = null, permissionModes = registry))

        val chip = assertNotNull(governed.permissionModes?.permissionMode?.first())
        assertEquals(AppServerPermissionMode.Unrestricted, chip.selected)
        assertNull(chips(FakeModelBackend(recordedModel = SOL)).permissionModes)
    }

    @Test
    fun noSourcesLeaveTheOptionsAsTheyAre() = runTest {
        val options = TimelineChatSessionOptions()

        assertSame(options, options.withChips(backgroundScope, target, chips = null))
        assertTrue(options.models == null && options.permissionModes == null)
    }

    private fun TestScope.chips(
        backend: FakeModelBackend,
        agentModel: String? = null,
        permissions: EnginePermissionModes? = null,
    ): TimelineChatSessionOptions = TimelineChatSessionOptions().withChips(
        backgroundScope,
        target,
        TimelineChipSources(
            models = TimelineModelSource(backend.backend, conversation = { backend.recorded(it) }, agentModel = agentModel),
            permissionModes = permissions,
        ),
    )

    private class FakeModelBackend(private var recordedModel: String?, private val refuse: Boolean = false) {
        val switches = mutableListOf<ConversationModelChange>()

        fun recorded(id: ConversationId) = Conversation(id = id, agentId = AgentId("agent-1"), model = recordedModel)

        val backend = ConversationModelBackend(
            list = { listOf(LlmModel(id = "sol", name = "Sol", handle = SOL), LlmModel(id = "minimax", name = "MiniMax", handle = MINIMAX)) },
            setModel = { change ->
                switches += change
                check(!refuse) { "refused" }
                recordedModel = change.model.value
                recorded(change.conversationId)
            },
        )
    }

    private companion object {
        const val SOL = "openai/gpt-sol"
        const val MINIMAX = "lmstudio/minimax-m3"
    }
}
