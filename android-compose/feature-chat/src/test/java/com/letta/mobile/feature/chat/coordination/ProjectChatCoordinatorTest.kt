package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.data.repository.api.IBugReportRepository
import com.letta.mobile.ui.chat.render.BugSeverity
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ProjectBriefSectionKey
import com.letta.mobile.ui.chat.render.ProjectBugReportDraft
import com.letta.mobile.ui.chat.render.ProjectChatContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProjectChatCoordinatorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val mockAgentRepository: IAgentRepository = mockk()
    private val mockBlockRepository: IBlockRepository = mockk()
    private val mockBugReportRepository: IBugReportRepository = mockk()
    
    private val testAgentId = "agent-123"
    private val testConversationId = "conv-456"
    private val testProjectContext = ProjectChatContext(
        identifier = "proj-xyz",
        name = "Test Project"
    )

    @Test
    fun `refreshContextWindow populates uiState correctly on success`() = testScope.runTest {
        val mockOverview = ContextWindowOverview(
            contextWindowSizeMax = 8192,
            contextWindowSizeCurrent = 1000,
            numMessages = 10,
            numTokensSystem = 100,
            numTokensCoreMemory = 200,
            numTokensExternalMemorySummary = 0,
            numTokensSummaryMemory = 0,
            numTokensFunctionsDefinitions = 300,
            numTokensToolUsageRules = 50,
            numTokensDirectories = 0,
            numTokensMemoryFilesystem = 0,
            numTokensMessages = 350,
            numArchivalMemory = 5,
            numRecallMemory = 15
        )
        coEvery { mockAgentRepository.getContextWindow(AgentId(testAgentId), ConversationId(testConversationId)) } returns mockOverview

        val uiState = MutableStateFlow(ChatUiState())
        var sentMessage: String? = null
        
        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = { sentMessage = it }
        )

        coordinator.refreshContextWindow()

        val state = uiState.value.contextWindow
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(8192, state.maxTokens)
        assertEquals(1000, state.currentTokens)
        assertEquals(10, state.messageCount)
        assertEquals(100, state.systemTokens)
        assertEquals(200, state.coreMemoryTokens)
        assertEquals(350, state.toolTokens)
        assertEquals(350, state.messageTokens)
        assertEquals(5, state.archivalMemoryCount)
        assertEquals(15, state.recallMemoryCount)
    }

    @Test
    fun `refreshContextWindow handles empty agent id correctly`() = testScope.runTest {
        val uiState = MutableStateFlow(ChatUiState())
        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = "",
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.refreshContextWindow()

        coVerify(exactly = 0) { mockAgentRepository.getContextWindow(any<AgentId>(), any<ConversationId>()) }
        assertFalse(uiState.value.contextWindow.isLoading)
    }

    @Test
    fun `refreshContextWindow populates error on failure`() = testScope.runTest {
        coEvery { mockAgentRepository.getContextWindow(AgentId(testAgentId), ConversationId(testConversationId)) } throws RuntimeException("API error")

        val uiState = MutableStateFlow(ChatUiState())
        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.refreshContextWindow()

        val state = uiState.value.contextWindow
        assertFalse(state.isLoading)
        assertTrue(state.error?.contains("Failed to load") == true)
    }

    @Test
    fun `submitStructuredBugReport populates uiState correctly and sends message`() = testScope.runTest {
        val draft = ProjectBugReportDraft(
            title = "Test Bug",
            description = "This is a test bug",
            severity = BugSeverity.High,
            tags = persistentListOf("ui", "crash"),
            attachmentReferences = persistentListOf()
        )
        
        val loggedReport = ProjectBugReport(
            id = 1L,
            projectIdentifier = testProjectContext.identifier,
            title = draft.title,
            description = draft.description,
            severity = draft.severity.wireValue,
            tags = draft.tags,
            attachmentReferences = draft.attachmentReferences,
            structuredPrompt = "test prompt",
            createdAt = Instant.now().toString()
        )

        coEvery { mockBugReportRepository.logBugReport(any()) } returns loggedReport

        val uiState = MutableStateFlow(ChatUiState())
        var sentMessage: String? = null

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = { sentMessage = it }
        )

        coordinator.submitStructuredBugReport(draft)

        val state = uiState.value.bugReports
        assertFalse(state.isSubmitting)
        assertNull(state.error)
        assertEquals(1, state.recentReports.size)
        assertEquals(1L, state.recentReports[0].id)
        assertNotNull(sentMessage)
        assertTrue(sentMessage?.contains("Bug Report: Test Bug") == true)
        assertTrue(sentMessage?.contains("Severity: high") == true)
        assertTrue(sentMessage?.contains("Tags: ui, crash") == true)
        assertTrue(sentMessage?.contains("This is a test bug") == true)
    }

    @Test
    fun `submitStructuredBugReport populates error on failure`() = testScope.runTest {
        val draft = ProjectBugReportDraft(
            title = "Test Bug",
            description = "This is a test bug"
        )
        
        coEvery { mockBugReportRepository.logBugReport(any()) } throws RuntimeException("DB error")

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.submitStructuredBugReport(draft)

        val state = uiState.value.bugReports
        assertFalse(state.isSubmitting)
        assertTrue(state.error?.contains("Failed to submit") == true)
    }

    @Test
    fun `submitStructuredBugReport does nothing when projectContext is null`() = testScope.runTest {
        val draft = ProjectBugReportDraft(
            title = "Test Bug",
            description = "This is a test bug"
        )
        
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = null,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.submitStructuredBugReport(draft)

        coVerify(exactly = 0) { mockBugReportRepository.logBugReport(any()) }
        assertFalse(uiState.value.bugReports.isSubmitting)
    }

    @Test
    fun `loadRecentBugReports populates uiState correctly`() = testScope.runTest {
        val reports = listOf(
            ProjectBugReport(
                id = 1L,
                projectIdentifier = testProjectContext.identifier,
                title = "Bug 1",
                description = "Desc 1",
                severity = "high",
                tags = emptyList(),
                attachmentReferences = emptyList(),
                structuredPrompt = "",
                createdAt = Instant.now().toString()
            )
        )
        
        coEvery { mockBugReportRepository.getRecentBugReports(testProjectContext.identifier) } returns reports

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadRecentBugReports()

        val state = uiState.value.bugReports
        assertNull(state.error)
        assertEquals(1, state.recentReports.size)
        assertEquals(1L, state.recentReports[0].id)
    }

    @Test
    fun `loadRecentBugReports populates error on failure`() = testScope.runTest {
        coEvery { mockBugReportRepository.getRecentBugReports(testProjectContext.identifier) } throws RuntimeException("DB error")

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadRecentBugReports()

        val state = uiState.value.bugReports
        assertTrue(state.error?.contains("Failed to load") == true)
    }

    @Test
    fun `loadRecentBugReports does nothing when projectContext is null`() = testScope.runTest {
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = null,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadRecentBugReports()

        coVerify(exactly = 0) { mockBugReportRepository.getRecentBugReports(any()) }
    }

    @Test
    fun `loadProjectBrief populates uiState correctly based on aliases`() = testScope.runTest {
        val blocks = listOf(
            Block(label = "project_description", value = "The desc", id = BlockId("b1"), limit = 1000),
            Block(label = "key-decisions", value = "The decisions", id = BlockId("b2"), limit = 1000),
            Block(label = "technology_stack", value = "The stack", id = BlockId("b3"), limit = 1000),
            Block(label = "current_goals", value = "The goals", id = BlockId("b4"), limit = 1000),
            Block(label = "latest_changes", value = "The changes", id = BlockId("b5"), limit = 1000),
            Block(label = "unknown_block", value = "Unknown", id = BlockId("b6"), limit = 1000)
        )
        
        coEvery { mockBlockRepository.getBlocks(testAgentId) } returns blocks

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadProjectBrief()

        val state = uiState.value.projectBrief
        assertFalse(state.isLoading)
        assertNull(state.error)
        
        assertEquals(5, state.sections.size)
        assertEquals("The desc", state.sections[ProjectBriefSectionKey.Description]?.content)
        assertEquals("The decisions", state.sections[ProjectBriefSectionKey.KeyDecisions]?.content)
        assertEquals("The stack", state.sections[ProjectBriefSectionKey.TechStack]?.content)
        assertEquals("The goals", state.sections[ProjectBriefSectionKey.ActiveGoals]?.content)
        assertEquals("The changes", state.sections[ProjectBriefSectionKey.RecentChanges]?.content)
    }

    @Test
    fun `loadProjectBrief populates error on failure`() = testScope.runTest {
        coEvery { mockBlockRepository.getBlocks(testAgentId) } throws RuntimeException("DB error")

        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadProjectBrief()

        val state = uiState.value.projectBrief
        assertFalse(state.isLoading)
        assertTrue(state.error?.contains("Failed to load") == true)
    }

    @Test
    fun `loadProjectBrief does nothing when projectContext is null`() = testScope.runTest {
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = null,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        coordinator.loadProjectBrief()

        coVerify(exactly = 0) { mockBlockRepository.getBlocks(any<String>()) }
    }
    
    @Test
    fun `saveProjectBriefSection updates block and state correctly`() = testScope.runTest {
        val initialBlocks = listOf(
            Block(label = "project_description", value = "Old desc", id = BlockId("b1"), limit = 1000),
        )
        coEvery { mockBlockRepository.getBlocks(testAgentId) } returns initialBlocks
        
        val updatedBlock = Block(label = "project_description", value = "New desc", id = BlockId("b1"), limit = 1000, updatedAt = "2023-01-01T00:00:00Z")
        coEvery { mockBlockRepository.updateAgentBlock(testAgentId, "project_description", any()) } returns updatedBlock
        
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        // Initial load to set up the state
        coordinator.loadProjectBrief()
        
        // Now save the section
        coordinator.saveProjectBriefSection(ProjectBriefSectionKey.Description, "New desc")
        
        val state = uiState.value.projectBrief
        assertFalse(state.isSaving)
        assertNull(state.error)
        assertEquals("New desc", state.sections[ProjectBriefSectionKey.Description]?.content)
        assertEquals("2023-01-01T00:00:00Z", state.sections[ProjectBriefSectionKey.Description]?.updatedAt)
        
        coVerify { mockBlockRepository.updateAgentBlock(testAgentId, "project_description", BlockUpdateParams("New desc")) }
    }

    @Test
    fun `saveProjectBriefSection populates error on failure`() = testScope.runTest {
        val initialBlocks = listOf(
            Block(label = "project_description", value = "Old desc", id = BlockId("b1"), limit = 1000),
        )
        coEvery { mockBlockRepository.getBlocks(testAgentId) } returns initialBlocks
        
        coEvery { mockBlockRepository.updateAgentBlock(testAgentId, "project_description", any()) } throws RuntimeException("DB error")
        
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        // Initial load to set up the state
        coordinator.loadProjectBrief()
        
        // Now save the section
        coordinator.saveProjectBriefSection(ProjectBriefSectionKey.Description, "New desc")
        
        val state = uiState.value.projectBrief
        assertFalse(state.isSaving)
        assertTrue(state.error?.contains("Failed to save") == true)
        assertEquals("Old desc", state.sections[ProjectBriefSectionKey.Description]?.content)
    }

    @Test
    fun `saveProjectBriefSection does nothing when section is not in state`() = testScope.runTest {
        val initialBlocks = listOf(
            Block(label = "project_description", value = "Old desc", id = BlockId("b1"), limit = 1000),
        )
        coEvery { mockBlockRepository.getBlocks(testAgentId) } returns initialBlocks
        
        val uiState = MutableStateFlow(ChatUiState())

        val coordinator = ProjectChatCoordinator(
            scope = testScope,
            agentId = testAgentId,
            projectContext = testProjectContext,
            uiState = uiState,
            agentRepository = mockAgentRepository,
            blockRepository = mockBlockRepository,
            bugReportRepository = mockBugReportRepository,
            conversationId = { testConversationId },
            setComposerError = {},
            sendMessage = {}
        )

        // Initial load to set up the state
        coordinator.loadProjectBrief()
        
        // Attempt to save a section that doesn't exist in the loaded blocks
        coordinator.saveProjectBriefSection(ProjectBriefSectionKey.KeyDecisions, "New decisions")
        
        coVerify(exactly = 0) { mockBlockRepository.updateAgentBlock(any(), any(), any()) }
    }
}
