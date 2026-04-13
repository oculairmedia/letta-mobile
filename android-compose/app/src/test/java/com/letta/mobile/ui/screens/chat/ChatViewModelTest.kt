package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.protocol.BotAgentInfo
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
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.TestData
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var blockRepository: BlockRepository
    private lateinit var bugReportRepository: BugReportRepository
    private lateinit var folderRepository: FolderRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var botGateway: BotGateway
    private lateinit var botConfigStore: BotConfigStore
    private lateinit var internalBotClient: InternalBotClient
    private val testDispatcher = UnconfinedTestDispatcher()
    private var messages: List<AppMessage> = emptyList()
    private var streamStates: List<StreamState> = emptyList()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        messageRepository = mockk(relaxed = true)
        agentRepository = mockk(relaxed = true)
        blockRepository = BlockRepository(FakeBlockApi())
        bugReportRepository = mockk(relaxed = true)
        folderRepository = FolderRepository(FakeFolderApi())
        conversationRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        botGateway = mockk(relaxed = true)
        botConfigStore = mockk(relaxed = true)
        internalBotClient = mockk(relaxed = true)

        every { settingsRepository.getChatBackgroundKey() } returns flowOf("default")

        every { messageRepository.getMessages(any(), any()) } answers { flowOf(messages) }
        coEvery { messageRepository.fetchMessages(any(), any()) } answers { messages }
        every { messageRepository.sendMessage(any(), any(), any()) } answers {
            flow {
                streamStates.forEach { emit(it) }
            }
        }
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        agentId: String = "agent-1",
        conversationId: String? = "conv-1",
    ): ChatViewModel {
        val savedState = SavedStateHandle().apply {
            set("agentId", agentId)
            conversationId?.let { set("conversationId", it) }
        }
        return ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
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
    fun `sendMessage shows user message immediately`() = runTest {
        messages = emptyList()
        streamStates = listOf(StreamState.Sending)

        val vm = createViewModel()
        vm.sendMessage("Hello agent")
        val state = vm.uiState.value

        assertTrue(state.messages.any { it.content == "Hello agent" && it.role == "user" })
    }

    @Test
    fun `sendMessage appends response after user message and history`() = runTest {
        val existingMessages = listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "First question"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "First answer"),
        )
        val streamResponse = listOf(
            TestData.appMessage(id = "3", messageType = MessageType.ASSISTANT, content = "Second answer"),
        )

        messages = existingMessages
        streamStates = listOf(StreamState.Complete(streamResponse))

        val vm = createViewModel()
        assertEquals(2, vm.uiState.value.messages.size)

        // Update fetchMessages to return what server would have after the send
        messages = existingMessages + listOf(
            TestData.appMessage(id = "user-q2", messageType = MessageType.USER, content = "Second question"),
        ) + streamResponse

        vm.sendMessage("Second question")
        val state = vm.uiState.value

        assertTrue(state.messages.size >= 4)
        assertEquals("First question", state.messages[0].content)
        assertEquals("First answer", state.messages[1].content)
        assertEquals("Second question", state.messages[2].content)
        assertEquals("Second answer", state.messages[3].content)
    }

    @Test
    fun `sendMessage does NOT replace history with streaming response`() = runTest {
        val history = listOf(
            TestData.appMessage(id = "old-1", messageType = MessageType.USER, content = "Old message 1"),
            TestData.appMessage(id = "old-2", messageType = MessageType.ASSISTANT, content = "Old reply 1"),
        )
        val streamChunk = listOf(
            TestData.appMessage(id = "new-1", messageType = MessageType.ASSISTANT, content = "New partial"),
        )

        messages = history
        streamStates = listOf(StreamState.Streaming(streamChunk))

        val vm = createViewModel()
        assertEquals(2, vm.uiState.value.messages.size)

        vm.sendMessage("New question")
        val state = vm.uiState.value

        assertTrue(state.messages.size >= 4)
        assertEquals("Old message 1", state.messages[0].content)
        assertEquals("Old reply 1", state.messages[1].content)
        assertEquals("New question", state.messages[2].content)
        assertEquals("New partial", state.messages[3].content)
    }

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

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
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

    @Test
    fun `project chat sends through embedded bot gateway`() = runTest {
        val session = mockk<BotSession>()
        val requestSlot = slot<com.letta.mobile.bot.protocol.BotChatRequest>()
        every { botGateway.getSession("agent-1") } returns session
        coEvery { internalBotClient.sendMessage(capture(requestSlot)) } returns BotChatResponse(
            response = "Gateway reply",
            conversationId = "conv-1",
            agentId = "agent-1",
        )

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
        )
        advanceUntilIdle()

        vm.sendMessage("Ship it")
        advanceUntilIdle()

        coVerify(exactly = 1) { internalBotClient.sendMessage(any()) }
        verify(exactly = 0) { messageRepository.sendMessage(any(), any(), any()) }
        assertEquals("agent-1", requestSlot.captured.agentId)
        assertEquals("conv-1", requestSlot.captured.conversationId)
        assertEquals("letta-mobile", requestSlot.captured.chatId)
        assertEquals("Letta Mobile", requestSlot.captured.senderName)
        assertTrue(vm.uiState.value.messages.any { it.content == "Gateway reply" && it.role == "assistant" })
        assertFalse(vm.uiState.value.isStreaming)
        assertFalse(vm.uiState.value.isAgentTyping)
    }

    @Test
    fun `project chat shows error when embedded bot is not configured`() = runTest {
        every { botGateway.getSession("agent-1") } returns null
        coEvery { botConfigStore.getAll() } returns emptyList()

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
        )
        advanceUntilIdle()

        vm.sendMessage("Ship it")
        advanceUntilIdle()

        assertEquals(
            "Project chat requires an enabled embedded bot for this agent. Configure it in Bot Settings first.",
            vm.uiState.value.error,
        )
        coVerify(exactly = 0) { internalBotClient.sendMessage(any()) }
    }

    @Test
    fun `loadMessages forwards scroll target to repository fetch`() = runTest {
        val targetSlot = slot<String?>()
        coEvery {
            messageRepository.fetchMessages(any(), any(), captureNullable(targetSlot))
        } answers { messages }

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("scrollToMessageId", "msg-target")
        }

        ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
        )
        advanceUntilIdle()

        assertEquals("msg-target", targetSlot.captured)
    }

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

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
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

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
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

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
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

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
        )

        assertTrue(vm.tryHandleSlashCommand("/bug"))
        assertFalse(vm.tryHandleSlashCommand("hello"))
    }

    @Test
    fun `submitStructuredBugReport sends formatted prompt through project gateway`() = runTest {
        val session = mockk<BotSession>()
        val requestSlot = slot<com.letta.mobile.bot.protocol.BotChatRequest>()
        every { botGateway.getSession("agent-1") } returns session
        coEvery { internalBotClient.sendMessage(capture(requestSlot)) } returns BotChatResponse(
            response = "Triaged bug report",
            conversationId = "conv-1",
            agentId = "agent-1",
        )

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = ChatViewModel(
            savedState,
            messageRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            botGateway,
            botConfigStore,
            internalBotClient,
        )
        advanceUntilIdle()

        vm.submitStructuredBugReport(
            ProjectBugReportDraft(
                title = "Crash on sync",
                description = "App crashes after project sync finishes.",
                severity = BugSeverity.High,
                tags = listOf("sync", "crash"),
                attachmentReferences = listOf("recording://screen-1"),
            )
        )
        advanceUntilIdle()

        val sentMessage = requestSlot.captured.message
        assertTrue(sentMessage.contains("Bug Report: Crash on sync"))
        assertTrue(sentMessage.contains("Severity: high"))
        assertTrue(sentMessage.contains("Tags: sync, crash"))
        assertTrue(sentMessage.contains("recording://screen-1"))
        assertEquals(sentMessage, vm.uiState.value.bugReports.lastSubmittedPrompt)
        assertTrue(vm.uiState.value.bugReports.recentReports.any { it.title == "Crash on sync" })
    }
}
