package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.model.process.ProcessId

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.feature.chat.coordination.ChatComposerEffect
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.EffortSelection
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.FixtureSession
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.testutil.TestData
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.feature.chat.coordination.ChatConversationBranching
import com.letta.mobile.ui.chat.session.ChatBranchSupport
import com.letta.mobile.ui.chat.session.ChatBranchView
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.ui.chat.session.ChatExtensionActionState
import com.letta.mobile.ui.chat.session.ChatExtensionFacts
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions
import com.letta.mobile.ui.chat.session.TimelineLocalState
import com.letta.mobile.ui.chat.session.withExtensions
import com.letta.mobile.ui.chat.session.ChatModelHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.feature.chat.coordination.ChatApprovalRelay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatSessionPortTest {

    @Test
    fun `process shelf and Stop use the common host projection`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val statuses = com.letta.mobile.data.transport.api.ToolsetStatusStore()
            val relay = mockk<ChatApprovalRelay>(relaxed = true)
            every { relay.deviceStatuses } returns statuses.deviceStatuses
            every { relay.pendingDetails } returns MutableStateFlow(emptyMap())
            every { relay.outbox.queuedRequestIds } returns MutableStateFlow(emptySet())
            every { relay.outbox.notices } returns MutableSharedFlow()
            every { relay.permissionMode(any()) } returns flowOf(null)
            val stopped = CompletableDeferred<Unit>()
            coEvery { relay.stopBackgroundProcess(any(), any()) } coAnswers { stopped.complete(Unit); Unit }
            val agent = TestData.agent("agent-process", "Process")
            val vm = openedChatViewModel(canonicalPagingHost(), agent, "conversation-process",
                FixtureSession("process", approvalRelay = relay))
            viewModel = vm
            val port = AdminChatSessionPort(vm, backgroundScope)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.composer.collect {} }
            // The host's device status listing these running processes, as it arrives on the wire.
            fun status(running: List<ProcessId>) = AppServerProtocol.decodeFrame(
                """{"type":"update_device_status","event_seq":1,"emitted_at":"now","idempotency_key":"status",
                    "runtime":{"agent_id":"agent-process","conversation_id":"conversation-process"},
                    "device_status":{"background_processes":[${running.joinToString(",") { """{"id":"${it.value}"}""" }}]}}""",
            )
            statuses.observe(status(listOf(ProcessId("p"))))
            assertEquals(listOf("p"), port.composer.first { it.backgroundProcesses.isNotEmpty() }.backgroundProcesses.map { it.id })
            port.actions.stopBackgroundProcess(ProcessId("p"))
            port.actions.stopBackgroundProcess(ProcessId("p"))
            stopped.await()
            coVerify(exactly = 1) { relay.stopBackgroundProcess(AppServerRuntimeScope("agent-process", "conversation-process"), "p") }
            assertEquals(1, port.composer.value.backgroundProcesses.size)
            statuses.observe(status(emptyList()))
            assertTrue(port.composer.first { it.backgroundProcesses.isEmpty() }.backgroundProcesses.isEmpty())
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `fork, edit and rerun come and go together with the page's ability to branch`() {
        val on = AdminChatSessionPort.Capabilities.withExtensions(ChatExtensionFacts(branching = ChatBranchSupport(fork = true)))
        val off = AdminChatSessionPort.Capabilities.withExtensions(ChatExtensionFacts())

        assertTrue(on.fork && on.editAndResend && on.rerunInFork)
        assertFalse(off.fork || off.editAndResend || off.rerunInFork)
        assertTrue("send again stays: it is not a branch", off.rerun)
    }

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
    fun `the page knows the route's agent from the first state`() = runTest {
        // letta-mobile-bglj6.1: the Touch chat head and the composer companion draw the agent's
        // mascot from uiState.agentId; null drew the stand-in sphere for every agent.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-mascot", "Meridian")
            val vm = openedChatViewModel(canonicalPagingHost(), agent, "conversation-mascot", "mascot")
            viewModel = vm
            val port = AdminChatSessionPort(vm, vm.viewModelScope)
            assertEquals(agent.id.value, port.uiState.value.agentId)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `send submits the current draft through submitComposer`() {
        val vm = mockViewModel(ChatComposerState(inputText = "draft"))
        every { vm.submitComposer(any()) } returns null

        actions(vm).send()

        verify(exactly = 1) { vm.submitComposer("draft") }
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test
    fun `goal refresh and continue forward to the view model and the port offers goals`() {
        val vm = mockViewModel(ChatComposerState())
        val actions = actions(vm)

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

        actions(vm, onOpenBugReport = { opened++ }).send()

        assertEquals(1, opened)
    }

    @Test
    fun `slash commands map to composer commands and run the matching command`() {
        val skill = SlashCommand(name = "review", command = "/review", description = "Review", installed = true)
        val other = SlashCommand(name = "plan", command = "/plan")
        val state = ChatComposerState(slashCommands = persistentListOf(skill, other))
        val vm = mockViewModel(state)
        val host = AdminChatComposerMapping.hostCommands(state.slashCommands, false, vm::selectSlashCommand, vm::uninstallSlashCommand)
        val actions = actions(vm, TimelineChatSessionOptions(composerHost = MutableStateFlow(ChatComposerHostInputs(host))))

        val commands = host.map { it.command }
        assertEquals(ChatComposerCommand("/review", "/review", "Review", fillsComposer = true, removable = true), commands.first())
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
    fun `slash fork is offered where the page can fork and forks instead of sending`() {
        // letta-mobile-7mys7: letta-code runs /fork in its terminal only, so the client runs it.
        val state = ChatComposerState(inputText = "/fork Plan B")
        val vm = mockViewModel(state)
        val branching = mockk<ChatConversationBranching.Page>(relaxed = true)
        val actions = actions(vm, branching = branching)

        fun commands(canFork: Boolean) = AdminChatComposerMapping.hostCommands(emptyList(), canFork, {}, {}).map { it.command }
        assertEquals(listOf(ChatComposerCommand.Fork), commands(canFork = true))
        assertEquals(emptyList<ChatComposerCommand>(), commands(canFork = false))

        actions.send()

        verify(exactly = 1) { branching.forkConversation("Plan B", any()) }
        verify(exactly = 0) { vm.submitComposer(any()) }
    }

    @Test
    fun `model selection maps each reasoning effort choice`() = runTest {
        val vm = mockViewModel(ChatComposerState())
        every { vm.activeAgent } returns MutableStateFlow(null)
        every { vm.llmModels } returns MutableStateFlow(emptyList())
        every { vm.conversationModelSelections } returns MutableStateFlow(ConversationModelChoices())
        val actions = actions(vm, TimelineChatSessionOptions(models = AdminChatModels(vm, backgroundScope)))

        actions.selectModel(ChatModelHandle("openai/gpt"), ReasoningEffortChoice.Named("high"))
        actions.selectModel(ChatModelHandle("openai/gpt"), ReasoningEffortChoice.ProviderDefault)
        actions.selectModel(ChatModelHandle("openai/gpt"), ReasoningEffortChoice.Unchanged)

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

    // letta-mobile-by4vm.82: the chip read Default whenever the conversation had its own model, so
    // an effort picked for it never showed and seemed not to stick.
    @Test
    fun `model state reports the conversation's effort under an override`() {
        val agent = TestData.agent("agent-model", "Model", model = "anthropic/agent-model")
        val inputs = AdminChatComposerMapping.ModelInputs(
            agent = agent,
            models = listOf(LlmModel(id = "m1", handle = "anthropic/claude-opus-5-5", displayNameOverride = "Claude Opus 5.5")),
            conversationOverride = "anthropic/claude-opus-5-5",
            effortsFor = { listOf("low", "medium", "high") },
            conversationEffort = "high",
        )

        val model = checkNotNull(AdminChatComposerMapping.modelUiState(inputs))

        assertEquals("Claude Opus 5.5", model.currentLabel)
        assertEquals("high", model.currentEffort)
        assertEquals(null, AdminChatComposerMapping.modelUiState(inputs.copy(conversationEffort = null))?.currentEffort)
    }

    @Test
    fun `the context chip shows the streamed total without any drawer refresh`() = runTest {
        // letta-mobile-0ofhc: nothing but the streamed reading feeds the chip. No refresh call,
        // no hamburger: a reading for the open conversation is on the composer once settled.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-context", "Context").copy(contextWindowLimit = 128_000)
            val readings = ContextTokenReadings()
            val vm = openedChatViewModel(canonicalPagingHost(), agent, "conversation-context", FixtureSession("context", readings))
            viewModel = vm
            val port = AdminChatSessionPort(vm, vm.viewModelScope)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.composer.collect {} }

            val placeholder = port.composer.value.contextUsage
            assertEquals(null, placeholder?.usage)
            assertTrue("the chip shows its placeholder rather than hiding", placeholder != null)

            val runtime = AppServerRuntimeScope("agent-context", "conversation-context")
            readings.record(usage(runtime, 28_864))
            readings.record(usage(runtime, 29_193))

            val usage = port.composer.value.contextUsage?.usage
            assertEquals(29_193, usage?.usedTokens)
            assertEquals(128_000, usage?.maxTokens)
            assertEquals(listOf(ContextWindowUsage.IN_CONTEXT_LABEL), usage?.segments?.map { it.label })
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `host advertised command runs from the Android composer through the graph adapter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val channel = SlashCommandChannel()
            val api = com.letta.mobile.data.session.graph.SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
            val vm = openedChatViewModel(
                canonicalPagingHost(), TestData.agent("agent-command", "Command"), "conversation-command",
                FixtureSession("commands", slashCommands = api),
            )
            viewModel = vm
            val owner = AdminChatSessionPort(vm, vm.viewModelScope)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.composer.collect {} }
            val port = com.letta.mobile.data.chat.routing.SlashCommandSessionPort(owner, vm.viewModelScope, AdminSlashCommandBinding(vm))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
            port.actions.updateComposerText("/deploy staging --check")
            port.actions.send()
            val params = channel.executions.single()
            assertEquals("deploy", params.getValue("command_id").jsonPrimitive.content)
            assertEquals("staging --check", params.getValue("args").jsonPrimitive.content)
            assertEquals("agent-command", params.getValue("agent_id").jsonPrimitive.content)
            assertEquals("conversation-command", params.getValue("conversation_id").jsonPrimitive.content)
            assertEquals("", vm.composerState.value.inputText)
            assertEquals("deployed", port.liveStatus.value.commands.single().output)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    private class SlashCommandChannel : com.letta.mobile.data.transport.api.NoOpChannelTransport() {
        val executions = mutableListOf<kotlinx.serialization.json.JsonObject>()
        override suspend fun adminRpc(method: String, path: String, body: String?):
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse {
            val result = when (method) {
                "slash_command.list_agent" -> kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"commands":[{"command":"/deploy","description":"Deploy safely","source":"mod","installed":true}]}""",
                )
                "slash_command.execute" -> {
                    executions += kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(body)).jsonObject
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"output":"deployed"}""")
                }
                else -> error("Unexpected RPC: $method")
            }
            return com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse("command", true, result = result)
        }
    }

    private fun usage(runtime: AppServerRuntimeScope, contextTokens: Long) =
        ServerFrame.UsageStatistics(agentId = runtime.agentId, conversationId = runtime.conversationId, contextTokens = contextTokens)

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
        val presentation = ChatPagingPresentation(
            settled = flowOf(PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = {},
        )
        return ChatPagingHost().apply { openCanonical = { _, _, _, _ -> presentation } }
    }

    private fun mockViewModel(state: ChatComposerState): AdminChatViewModel = mockk(relaxed = true) {
        every { composerState } returns MutableStateFlow(state)
    }

    private fun actions(
        vm: AdminChatViewModel,
        options: TimelineChatSessionOptions = TimelineChatSessionOptions(),
        branching: ChatConversationBranching.Page? = null,
        onOpenBugReport: () -> Unit = {},
    ) = AdminChatActions(
        vm,
        options,
        ChatExtensionActionState(MutableStateFlow(TimelineLocalState()), { ChatBranchView(emptyList(), false) }, { null }),
        onOpenBugReport,
        branching,
    )
}
