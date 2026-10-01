package com.letta.mobile.feature.chat.screen.shared

import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatTimelinePresentation
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatSessionPortTest {

    @Test
    fun `composer mirrors the view model draft and attachment limit`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-port", "Port")
            val vm = openedChatViewModel(canonicalPagingHost(), agent, "conversation-port", "port")
            viewModel = vm
            val port = AdminChatSessionPort(vm, vm.viewModelScope)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.composer.collect {} }

            // Everything runs on unconfined dispatchers: no scheduler advance, which would also
            // release the fixture's relaxed timeline mocks.
            port.actions.updateComposerText("hello shared page")

            assertEquals("hello shared page", vm.composerState.value.inputText)
            val composer = port.composer.value
            assertEquals("hello shared page", composer.text)
            assertEquals(vm.attachmentLimits.maxAttachmentCount, composer.maxAttachments)
            assertEquals(null, composer.workingDirectory)
            assertTrue(port.capabilities.value.search)
            assertFalse(port.capabilities.value.workingDirectory)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `send submits the current draft through submitComposer`() {
        val vm = mockViewModel(ChatComposerState(inputText = "draft"))
        every { vm.submitComposer(any()) } returns null

        AdminChatActions(vm, onOpenBugReport = {}).send()

        verify(exactly = 1) { vm.submitComposer("draft") }
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test
    fun `goal refresh and continue forward to the view model and the port offers goals`() {
        val vm = mockViewModel(ChatComposerState())
        val actions = AdminChatActions(vm, onOpenBugReport = {})

        actions.refreshGoalStatus()
        actions.continueGoal()

        verify(exactly = 1) { vm.refreshGoalStatus() }
        verify(exactly = 1) { vm.continueGoal() }
        assertTrue(AdminChatSessionPort.Capabilities.goals)
    }

    @Test
    fun `bug report effect from the composer reaches the host`() {
        val vm = mockViewModel(ChatComposerState(inputText = "/bug"))
        every { vm.submitComposer(any()) } returns ChatComposerEffect.OpenBugReport
        var opened = 0

        AdminChatActions(vm, onOpenBugReport = { opened++ }).send()

        assertEquals(1, opened)
    }

    @Test
    fun `slash commands map to composer commands and run the matching command`() {
        val skill = SlashCommand(name = "review", command = "/review", description = "Review", installed = true)
        val other = SlashCommand(name = "plan", command = "/plan")
        val state = ChatComposerState(slashCommands = persistentListOf(skill, other))
        val vm = mockViewModel(state)
        val actions = AdminChatActions(vm, onOpenBugReport = {})

        val commands = AdminChatComposerMapping.composerUiState(composerInputs(state)).commands
        assertEquals(listOf("/review", "/plan"), commands.map { it.id })
        assertTrue(commands.first().fillsComposer)
        assertTrue(commands.first().removable)
        assertFalse(commands.last().removable)

        actions.runComposerCommand(commands.first())
        actions.uninstallComposerCommand(commands.first())
        actions.runComposerCommand(ChatComposerCommand(id = "/missing", label = "/missing"))

        verify(exactly = 1) { vm.selectSlashCommand(skill) }
        verify(exactly = 1) { vm.uninstallSlashCommand(skill) }
        verify(exactly = 1) { vm.selectSlashCommand(any()) }
    }

    @Test
    fun `model selection maps each reasoning effort choice`() {
        val vm = mockViewModel(ChatComposerState())
        val actions = AdminChatActions(vm, onOpenBugReport = {})

        actions.selectModel("openai/gpt", ReasoningEffortChoice.Named("high"))
        actions.selectModel("openai/gpt", ReasoningEffortChoice.ProviderDefault)
        actions.selectModel("openai/gpt", ReasoningEffortChoice.Unchanged)

        verify { vm.updateActiveAgentModel("openai/gpt", EffortSelection.Set("high")) }
        verify { vm.updateActiveAgentModel("openai/gpt", EffortSelection.Set(null)) }
        verify { vm.updateActiveAgentModel("openai/gpt", EffortSelection.Keep) }
    }

    @Test
    fun `model state prefers the conversation override and lists efforts per option`() {
        val agent = TestData.agent("agent-model", "Model", model = "anthropic/agent-model")
        val models = listOf(
            LlmModel(id = "m1", handle = "anthropic/agent-model", displayNameOverride = "Agent Model"),
            LlmModel(id = "m2", handle = "openai/override", displayNameOverride = "Override"),
        )
        val inputs = AdminChatComposerMapping.ModelInputs(
            agent = agent,
            models = models,
            conversationOverride = "openai/override",
            effortsFor = { handle -> if (handle == "openai/override") listOf("low", "high") else emptyList() },
        )

        val model = checkNotNull(AdminChatComposerMapping.modelUiState(inputs))

        assertEquals("openai/override", model.currentHandle)
        assertEquals("Override", model.currentLabel)
        assertEquals(listOf("low", "high"), model.options.single { it.handle == "openai/override" }.reasoningEfforts)
        assertEquals(
            "anthropic/agent-model",
            AdminChatComposerMapping.modelUiState(inputs.copy(conversationOverride = null))?.currentHandle,
        )
    }

    @Test
    fun `context window maps only when there is a reading, a load or an error`() {
        val empty = com.letta.mobile.ui.chat.render.ContextWindowUiState()
        assertEquals(null, AdminChatComposerMapping.contextUsage(empty))

        val usage = AdminChatComposerMapping.contextUsage(
            empty.copy(maxTokens = 1_000, currentTokens = 400, messageTokens = 300, systemTokens = 50),
        )
        assertEquals(1_000, usage?.usage?.maxTokens)
        assertEquals(400, usage?.usage?.usedTokens)
        assertEquals(true, AdminChatComposerMapping.contextUsage(empty.copy(isLoading = true))?.loading)
    }

    @Test
    fun `open canvas from the full-screen page goes to canvas navigation`() {
        val fullScreen = com.letta.mobile.ui.chat.session.ChatSurfacePresentation.ChatFirst
        val docked = com.letta.mobile.ui.chat.session.ChatSurfacePresentation.CanvasFirst
        val openCanvas = com.letta.mobile.ui.chat.session.ChatSurfaceIntent.OpenCanvas

        assertTrue(routesToCanvasNavigation(fullScreen, openCanvas))
        assertFalse(routesToCanvasNavigation(docked, openCanvas))
        assertFalse(routesToCanvasNavigation(fullScreen, com.letta.mobile.ui.chat.session.ChatSurfaceIntent.Collapse))
    }

    /** The canonical route, so the fixture never starts the legacy observer over relaxed mocks. */
    private fun canonicalPagingHost(): ChatPagingHost {
        val presentation = ChatTimelinePresentation(timeline = null, close = {})
        return ChatPagingHost().apply { openCanonical = { _, _, _, _ -> presentation } }
    }

    private fun mockViewModel(state: ChatComposerState): AdminChatViewModel = mockk(relaxed = true) {
        every { composerState } returns MutableStateFlow(state)
    }

    private fun composerInputs(state: ChatComposerState) = AdminChatComposerMapping.ComposerInputs(
        composer = state,
        canSend = true,
        canQueueWhileStreaming = false,
        maxAttachments = 4,
        model = null,
        contextWindow = com.letta.mobile.ui.chat.render.ContextWindowUiState(),
    )
}
