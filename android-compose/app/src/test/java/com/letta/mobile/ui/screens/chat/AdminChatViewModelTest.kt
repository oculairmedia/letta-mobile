package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotChatResponse
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationManager
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.TestData
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AdminChatViewModelTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var timelineRepository: com.letta.mobile.data.timeline.TimelineRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var blockRepository: BlockRepository
    private lateinit var bugReportRepository: BugReportRepository
    private lateinit var folderRepository: FolderRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationManager: ConversationManager
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var internalBotClient: InternalBotClient
    private lateinit var clientModeChatSender: ClientModeChatSender
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var clientModeEnabledFlow: MutableStateFlow<Boolean>
    private var messages: List<AppMessage> = emptyList()
    private var streamStates: List<StreamState> = emptyList()
    private val activeConversationIds = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        messageRepository = mockk(relaxed = true)
        timelineRepository = mockk(relaxed = true)
        // Timeline observer requires non-null Flows. By default, mirror the
        // current `messages` seed list (used by legacy tests) into a
        // Timeline.Confirmed snapshot so `_uiState.messages` stays populated.
        val emptyTimelineLoop = io.mockk.mockk<com.letta.mobile.data.timeline.TimelineSyncLoop>(relaxed = true) {
            every { events } returns kotlinx.coroutines.flow.MutableSharedFlow()
        }
        coEvery { timelineRepository.observe(any()) } answers {
            val convId = firstArg<String>()
            kotlinx.coroutines.flow.MutableStateFlow(messagesToTimeline(convId, messages))
        }
        coEvery { timelineRepository.getOrCreate(any()) } returns emptyTimelineLoop
        agentRepository = mockk(relaxed = true)
        blockRepository = BlockRepository(FakeBlockApi())
        bugReportRepository = mockk(relaxed = true)
        folderRepository = FolderRepository(FakeFolderApi())
        conversationRepository = mockk(relaxed = true)
        conversationManager = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        internalBotClient = mockk(relaxed = true)
        clientModeChatSender = mockk(relaxed = true)
        clientModeEnabledFlow = MutableStateFlow(false)
        activeConversationIds.clear()

        every { settingsRepository.getChatBackgroundKey() } returns flowOf("default")
        every { settingsRepository.getChatFontScale() } returns flowOf(1f)
        every { settingsRepository.observeClientModeEnabled() } returns clientModeEnabledFlow
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow { }
        every { conversationManager.getActiveConversationId(any()) } answers {
            activeConversationIds[firstArg()]
        }
        every { conversationManager.setActiveConversation(any(), any()) } answers {
            activeConversationIds[firstArg()] = secondArg()
            Unit
        }

        coEvery { messageRepository.fetchMessages(any(), any(), any()) } answers { messages }
        coEvery { messageRepository.fetchOlderMessages(any(), any(), any()) } returns emptyList()
        coEvery { messageRepository.submitApproval(any(), any(), any(), any(), any()) } returns Unit
        coEvery { bugReportRepository.getRecentBugReports(any(), any()) } returns emptyList()
        coEvery { bugReportRepository.logBugReport(any()) } answers { firstArg<ProjectBugReport>().copy(id = 1L) }
        every { agentRepository.getAgent(any()) } returns flowOf(TestData.agent(id = "agent-1", name = "Test Agent"))
        every { conversationRepository.getConversations(any()) } answers {
            flowOf(listOf(TestData.conversation(id = "conv-1", agentId = firstArg())))
        }
        coEvery { conversationRepository.refreshConversations(any()) } returns Unit
        coEvery { conversationRepository.createConversation(any(), any()) } answers {
            TestData.conversation(id = "new-conv", agentId = firstArg(), summary = secondArg())
        }
        coEvery { conversationRepository.updateConversation(any(), any(), any()) } returns Unit
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } answers {
            val agentId = firstArg<String>()
            val resolved = "conv-1"
            activeConversationIds[agentId] = resolved
            resolved
        }
        coEvery { conversationManager.createAndSetActiveConversation(any(), any()) } answers {
            val agentId = firstArg<String>()
            val summary = secondArg<String?>()
            val conversation = TestData.conversation(id = "new-conv", agentId = agentId, summary = summary)
            activeConversationIds[agentId] = conversation.id
            conversation.id
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        agentId: String = "agent-1",
        conversationId: String? = "conv-1",
        freshRouteKey: Long? = null,
    ): AdminChatViewModel {
        val savedState = SavedStateHandle().apply {
            set("agentId", agentId)
            conversationId?.let { set("conversationId", it) }
            freshRouteKey?.let { set("freshRouteKey", it) }
        }
        return AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
    }

    /**
     * Build a [com.letta.mobile.data.timeline.Timeline] from a legacy
     * [AppMessage] seed list. Only the fields consumed by
     * [AdminChatViewModel.startTimelineObserver] are populated.
     */
    private fun messagesToTimeline(
        conversationId: String,
        seed: List<AppMessage>,
    ): com.letta.mobile.data.timeline.Timeline {
        val events = seed.mapIndexed { index, message ->
            val msgType = when (message.messageType) {
                MessageType.USER -> com.letta.mobile.data.timeline.TimelineMessageType.USER
                MessageType.ASSISTANT -> com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT
                MessageType.REASONING -> com.letta.mobile.data.timeline.TimelineMessageType.REASONING
                MessageType.TOOL_CALL -> com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL
                MessageType.TOOL_RETURN -> com.letta.mobile.data.timeline.TimelineMessageType.TOOL_RETURN
                else -> com.letta.mobile.data.timeline.TimelineMessageType.OTHER
            }
            com.letta.mobile.data.timeline.TimelineEvent.Confirmed(
                position = (index + 1).toDouble(),
                otid = message.id,
                content = message.content,
                serverId = message.id,
                messageType = msgType,
                date = message.date ?: Instant.EPOCH,
                runId = null,
                stepId = null,
            )
        }
        return com.letta.mobile.data.timeline.Timeline(
            conversationId = conversationId,
            events = events,
        )
    }

    @Test
    fun `loadMessages populates messages and agent name`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "Hi"),
        )

        val vm = createViewModel()
        val state = vm.uiState.value

        assertEquals(2, state.messages.size)
        assertEquals("Test Agent", state.agentName)
        assertFalse(state.isLoadingMessages)
    }

    @Test
    fun `loadMessages uses cached conversation resolution without refresh when conversation exists`() = runTest {
        messages = listOf(TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"))

        createViewModel(conversationId = "conv-1")

        io.mockk.coVerify(exactly = 0) { conversationRepository.refreshConversations(any()) }
    }

    @Test
    fun `loadMessages preserves repository order instead of resorting by date`() = runTest {
        messages = listOf(
            AppMessage(
                id = "assistant-first",
                date = Instant.parse("2024-03-15T11:00:00Z"),
                messageType = MessageType.ASSISTANT,
                content = "Assistant came first in repository order",
            ),
            AppMessage(
                id = "user-second",
                date = Instant.parse("2024-03-15T10:00:00Z"),
                messageType = MessageType.USER,
                content = "User came second in repository order",
            ),
        )

        val vm = createViewModel()

        assertEquals(
            listOf("assistant-first", "user-second"),
            vm.uiState.value.messages.map { it.id },
        )
    }

    @Test
    fun `loadMessages sets error on failure`() = runTest {
        every { agentRepository.getAgent(any()) } returns flow { throw Exception("Failed to load agent") }

        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value

        assertTrue(state.error != null)
    }

    @Test
    fun `resolveConversationAndLoad exposes error state when conversation resolution fails`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } throws IllegalStateException("Resolver offline")

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        assertEquals(
            ConversationState.Error("Resolver offline"),
            vm.uiState.value.conversationState,
        )
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoadingMessages)
    }

    @Test
    fun `resolveConversationAndLoad exposes no conversation state when no conversation exists`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } returns null

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoadingMessages)
    }

    @Test
    fun `sendMessage is blocked while conversation resolution is in error state`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } throws IllegalStateException("Resolver offline")

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.sendMessage("Hello agent")

        assertEquals("Retry conversation loading before sending a message", vm.uiState.value.error)
    }

    // Legacy streaming-path send-flow tests (sendMessage shows pending bubble,
    // streaming response plumbing, reload merging) were removed in Phase 5 of
    // the Timeline migration. See letta-mobile-844e. Send behaviour is now
    // covered by TimelineSyncLoopTest (unit) and the full sync pipeline tests
    // in core/src/test/java/com/letta/mobile/data/timeline/.

    @Test
    fun `sendMessage clears input and sets streaming`() = runTest {
        messages = emptyList()
        streamStates = listOf(StreamState.Sending)

        val vm = createViewModel()
        vm.updateInputText("Hello")
        vm.sendMessage("Hello")

        assertEquals("", vm.inputText.value)
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage uses timeline path when client mode is disabled`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.sendMessage("Hello from timeline")
        advanceUntilIdle()

        verify(exactly = 0) {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            timelineRepository.sendMessage("conv-1", "Hello from timeline")
        }
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage routes through client mode sender when enabled`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
                forceFreshConversation = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "Hel", conversationId = "client-conv"))
            emit(BotStreamChunk(text = "Hello from client mode", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.sendMessage("Hello from client mode")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "Hello from client mode",
                conversationId = null,
                forceFreshConversation = true,
            )
        }
        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals("Hello from client mode", vm.uiState.value.messages.last().content)
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage rejects attachments in client mode`() = runTest {
        clientModeEnabledFlow.value = true
        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.addAttachment(
            com.letta.mobile.data.model.MessageContentPart.Image(
                base64 = "ZmFrZQ==",
                mediaType = "image/png",
            )
        )
        vm.sendMessage("hello")

        assertEquals(
            "Client Mode attachments are not supported yet",
            vm.uiState.value.composerError,
        )
    }

    @Test
    fun `resetMessages clears client mode conversation state`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
                forceFreshConversation = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "Hi", conversationId = "client-conv"))
            emit(BotStreamChunk(text = "Hi", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()
        vm.sendMessage("hello")
        advanceUntilIdle()

        vm.resetMessages()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `client mode renders tool call and tool result chunks as tool card`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_CALL,
                    toolName = "search",
                    toolCallId = "call-1",
                    toolInput = kotlinx.serialization.json.buildJsonObject {
                        put("query", kotlinx.serialization.json.JsonPrimitive("kotlin"))
                    },
                    conversationId = "client-conv",
                )
            )
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_RESULT,
                    toolName = "search",
                    toolCallId = "call-1",
                    text = "done",
                    isError = false,
                    conversationId = "client-conv",
                )
            )
            emit(BotStreamChunk(text = "Final answer", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()

        val toolMessage = vm.uiState.value.messages.firstOrNull { !it.toolCalls.isNullOrEmpty() }
        assertTrue(toolMessage != null)
        assertEquals("search", toolMessage!!.toolCalls!!.single().name)
        assertEquals("done", toolMessage.toolCalls!!.single().result)
    }

    @Test
    fun `toggling client mode off restores timeline messages after client mode session`() = runTest {
        clientModeEnabledFlow.value = true
        messages = listOf(
            TestData.appMessage(id = "timeline-user", messageType = MessageType.USER, content = "Timeline hello"),
            TestData.appMessage(id = "timeline-assistant", messageType = MessageType.ASSISTANT, content = "Timeline reply"),
        )
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = "Client reply", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.any { it.content == "Client reply" })

        clientModeEnabledFlow.value = false
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "timeline-user" })
        assertTrue(vm.uiState.value.messages.any { it.id == "timeline-assistant" })
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `blank conversation id in saved state behaves like fresh client mode route`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
                forceFreshConversation = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "Fresh route", conversationId = "client-conv", done = true))
        }

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "")
        }
        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = null,
                forceFreshConversation = true,
            )
        }
        coVerify(exactly = 0) { timelineRepository.sendMessage(any(), any()) }
        assertTrue(vm.uiState.value.messages.any { it.content == "Fresh route" })
    }

    @Test
    fun `fresh route key uses client mode before hydrating active timeline`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds["agent-1"] = "conv-1"
        messages = listOf(
            TestData.appMessage(id = "timeline-user", messageType = MessageType.USER, content = "Old timeline"),
        )
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
                forceFreshConversation = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "Fresh client reply", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 123L)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())

        vm.sendMessage("hello")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = null,
                forceFreshConversation = true,
            )
        }
        coVerify(exactly = 0) { timelineRepository.sendMessage(any(), any()) }
        assertTrue(vm.uiState.value.messages.none { it.content == "Old timeline" })
        assertTrue(vm.uiState.value.messages.any { it.content == "Fresh client reply" })
    }

    @Test
    fun `existing conversation route hydrates passed conversation instead of falling back to empty state`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "existing-user", messageType = MessageType.USER, content = "Earlier message"),
            TestData.appMessage(id = "existing-assistant", messageType = MessageType.ASSISTANT, content = "Existing reply"),
        )
        every { conversationManager.getActiveConversationId(any()) } returns null
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } returns null

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-user" })
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-assistant" })
        verify { conversationManager.setActiveConversation("agent-1", "conv-1") }
    }

    @Test
    fun `updateInputText only changes input field`() = runTest {
        messages = listOf(TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Msg"))

        val vm = createViewModel()
        vm.updateInputText("typing...")

        assertEquals("typing...", vm.inputText.value)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun `blank agentId sets error`() = runTest {
        val vm = createViewModel(agentId = "")
        assertTrue(vm.uiState.value.error != null)
    }

    @Test
    fun `project context is restored from saved state`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
            set("projectFilesystemPath", "/opt/stacks/letta-mobile")
            set("projectGitUrl", "https://github.com/letta-ai/letta-mobile")
            set("projectLastSyncAt", "2026-04-13T12:00:00Z")
            set("projectActiveCodingAgents", "android, pm-agent")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )

        val project = vm.projectContext
        assertEquals("letta-mobile", project?.identifier)
        assertEquals("Letta Mobile", project?.name)
        assertEquals("/opt/stacks/letta-mobile", project?.filesystemPath)
        assertEquals("https://github.com/letta-ai/letta-mobile", project?.gitUrl)
        assertEquals("2026-04-13T12:00:00Z", project?.lastSyncAt)
        assertEquals("android, pm-agent", project?.activeCodingAgents)
    }

    @Test
    fun `submitApproval forwards approval decision to repository`() = runTest {
        val vm = createViewModel()

        vm.submitApproval(
            requestId = "approval-1",
            toolCallIds = listOf("tool-call-1", "tool-call-2"),
            approve = false,
            reason = "Needs confirmation",
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            messageRepository.submitApproval(
                conversationId = "conv-1",
                approvalRequestId = "approval-1",
                toolCallIds = listOf("tool-call-1", "tool-call-2"),
                approve = false,
                reason = "Needs confirmation",
            )
        }
        assertFalse(vm.uiState.value.isStreaming)
        assertEquals(null, vm.uiState.value.activeApprovalRequestId)
    }

    // Project-chat embedded-bot-gateway send tests were removed in Phase 5 —
    // `sendMessage` now unconditionally routes through TimelineRepository.
    // Gateway integration coverage lives in `InternalBotClientTest`.

    // `loadMessages forwards scroll target to repository fetch` was removed in
    // Phase 5 — history loading now runs through TimelineRepository.hydrate(),
    // which handles scrollToMessageId internally; targeted fetch coverage
    // lives in MessageRepositoryE2eTest and TimelineRepository tests.

    // `loadOlderMessages prepends older history` and `...never produces
    // duplicate message ids` relied on the legacy fetchMessages pre-hydrate
    // setting hasMoreOlderMessages=true. That signal now needs to flow from
    // TimelineRepository pagination, which is a follow-up work item
    // (letta-mobile-lhki). Removed here in Phase 5 to unblock the
    // legacy-state cleanup; re-add once Timeline exposes a pagination cursor.

    // `loadOlderMessages skips fetch when initial page proves there is no
    // older history` was removed in letta-mobile-b1di. The 23h5 regression
    // fix (see AdminChatViewModel.kt lines 952-967) intentionally inverted
    // the contract: hasMoreOlderMessages is now optimistically flipped to
    // true any time the timeline has at least one confirmed message, and
    // the actual fetchOlderMessages call settles the truth (sets it back
    // to false when fewer than PAGE_SIZE rows come back, see line 688).
    // The deleted test asserted the pre-23h5 behavior — that single-message
    // initial pages skip the fetch entirely — which contradicts the fix.
    // Page-size pagination semantics are covered in MessageRepositoryE2eTest
    // (`fetchOlderMessages returns page ordered chronologically`), and the
    // optimistic-flip path is implicitly exercised by every other VM test
    // that loads messages. Re-add a positive-coverage test here once
    // TimelineRepository exposes an explicit pagination cursor (the same
    // follow-up referenced above as letta-mobile-lhki).

    @Test
    fun `project brief loads mapped sections from core memory blocks`() = runTest {
        val fakeBlockApi = FakeBlockApi().apply {
            blocks["agent-1"] = mutableListOf(
                Block(id = "b1", label = "project_description", value = "Ship the Android PM surface"),
                Block(id = "b2", label = "key_decisions", value = "- Use shared project chat\n- Keep brief editable"),
                Block(id = "b3", label = "tech_stack", value = "Kotlin\nCompose\nLetta API"),
                Block(id = "b4", label = "active_goals", value = "- Finish bz40.2.4"),
                Block(id = "b5", label = "recent_changes", value = "Added public proxy support"),
            )
        }
        blockRepository = BlockRepository(fakeBlockApi)

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        val brief = vm.uiState.value.projectBrief
        assertFalse(brief.isLoading)
        assertEquals("Ship the Android PM surface", brief.sections[ProjectBriefSectionKey.Description]?.content)
        assertEquals("Kotlin\nCompose\nLetta API", brief.sections[ProjectBriefSectionKey.TechStack]?.content)
        assertEquals(5, brief.sections.size)
    }

    @Test
    fun `save project brief updates matching memory block`() = runTest {
        val fakeBlockApi = FakeBlockApi().apply {
            blocks["agent-1"] = mutableListOf(
                Block(id = "b1", label = "project_description", value = "Old brief"),
            )
        }
        blockRepository = BlockRepository(fakeBlockApi)

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.saveProjectBriefSection(ProjectBriefSectionKey.Description, "Updated brief")
        advanceUntilIdle()

        assertEquals("updateAgentBlock:agent-1:project_description", fakeBlockApi.calls.last())
        assertEquals(BlockUpdateParams(value = "Updated brief"), fakeBlockApi.lastUpdateParams)
        assertEquals("Updated brief", vm.uiState.value.projectBrief.sections[ProjectBriefSectionKey.Description]?.content)
        assertFalse(vm.uiState.value.projectBrief.isSaving)
    }

    @Test
    fun `project agents load from folder membership and live bot status`() = runTest {
        folderRepository = FolderRepository(FakeFolderApi())
        every { agentRepository.getCachedAgent("agent-1") } returns null
        coEvery { internalBotClient.listAgents() } returns listOf(
            BotAgentInfo(id = "agent-1", name = "Coder", status = "working")
        )
        every { agentRepository.getAgent("agent-1") } returns flowOf(
            Agent(
                id = "agent-1",
                name = "Coder",
                model = "gpt-4.1",
                updatedAt = "2026-04-13T12:00:00Z",
            )
        )

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
            set("projectLettaFolderId", "folder-1")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        val state = vm.uiState.value.projectAgents
        assertFalse(state.isLoading)
        assertEquals(1, state.agents.size)
        assertEquals("Coder", state.agents.first().name)
        assertEquals("Working", state.agents.first().statusLabel)
        assertEquals("gpt-4.1", state.agents.first().model)
    }

    @Test
    fun `tryHandleSlashCommand is true only for project bug command`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )

        assertTrue(vm.tryHandleSlashCommand("/bug"))
        assertFalse(vm.tryHandleSlashCommand("hello"))
    }

    @Test
    fun `submitStructuredBugReport formats prompt and logs report`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.submitStructuredBugReport(
            ProjectBugReportDraft(
                title = "Crash on sync",
                description = "App crashes after project sync finishes.",
                severity = BugSeverity.High,
                tags = persistentListOf("sync", "crash"),
                attachmentReferences = persistentListOf("recording://screen-1"),
            )
        )
        advanceUntilIdle()

        // The structured prompt should be formatted, logged via the bug-report
        // repository, and surfaced on _uiState. The actual send now goes
        // through TimelineRepository (covered by TimelineSyncLoopTest).
        val submitted = vm.uiState.value.bugReports.lastSubmittedPrompt ?: ""
        assertTrue(submitted.contains("Bug Report: Crash on sync"))
        assertTrue(submitted.contains("Severity: high"))
        assertTrue(submitted.contains("Tags: sync, crash"))
        assertTrue(submitted.contains("recording://screen-1"))
        assertTrue(vm.uiState.value.bugReports.recentReports.any { it.title == "Crash on sync" })
    }

    /**
     * Regression for letta-mobile-nw2e.
     *
     * The prior `startTimelineObserver` used `if (timelineObserverJob?.isActive == true) return`,
     * which silently ignored conversation switches: once the observer was
     * bound to conv-A, selecting conv-B would NOT rebind. The user then sat
     * on conv-B's screen watching it never update because no TimelineSync
     * loop was ever started for conv-B.
     *
     * Acceptance: when the viewmodel is asked to resolve a different
     * conversation after the first one, `timelineRepository.observe(convB)`
     * AND `getOrCreate(convB)` must both be invoked at least once. Using
     * atLeast=1 so we're resilient to internal retries or repeated binds.
     */
    @Test
    fun `switching conversations triggers fresh timeline observer bind`() = runTest {
        // Seed the conversation manager to resolve to "conv-A" first.
        activeConversationIds["agent-1"] = "conv-A"

        val vm = createViewModel(agentId = "agent-1", conversationId = "conv-A")
        advanceUntilIdle()

        // Capture arguments of every observe() + getOrCreate() call the VM
        // has made so far. Baseline MUST include conv-A.
        coVerify(atLeast = 1) { timelineRepository.observe("conv-A") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-A") }

        // Simulate user picking conv-B in the conversation picker. The
        // production flow invokes conversationManager.setActiveConversation()
        // and then retryConversationLoad() (== resolveConversationAndLoad()).
        activeConversationIds["agent-1"] = "conv-B"
        vm.retryConversationLoad()
        advanceUntilIdle()

        // The fix: observer must rebind to conv-B. If nw2e regresses,
        // these verifications fail because the old `isActive == true`
        // guard short-circuits and conv-B is never observed.
        coVerify(atLeast = 1) { timelineRepository.observe("conv-B") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-B") }
    }

    /**
     * Regression for letta-mobile-nw2e (companion): staying on the SAME
     * conversation must remain idempotent. We don't want the fix to cause
     * a storm of rebinds if some caller invokes `startTimelineObserver`
     * repeatedly for the same id (e.g. send → start observer defensively).
     *
     * We check observe() is called at most a small bounded number of times
     * when the conversation id doesn't change. The exact count depends on
     * how many internal paths hit startTimelineObserver, but it must be
     * well under 10× the nominal single bind.
     */
    @Test
    fun `same-conversation repeat calls do not storm rebinds`() = runTest {
        activeConversationIds["agent-1"] = "conv-A"
        val vm = createViewModel(agentId = "agent-1", conversationId = "conv-A")
        advanceUntilIdle()

        // Simulate several calls in quick succession (same conv id). In
        // production these can happen when the user spam-taps a retry, or
        // the send path + the resolver both wire up the observer.
        repeat(5) {
            vm.retryConversationLoad()
            advanceUntilIdle()
        }

        // Hard upper bound: something is badly wrong if we've rebound more
        // than 10× for a single conversation.
        coVerify(atMost = 10) { timelineRepository.observe("conv-A") }
    }
}
