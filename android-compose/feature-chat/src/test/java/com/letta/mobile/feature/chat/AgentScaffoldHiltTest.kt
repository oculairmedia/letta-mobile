package com.letta.mobile.feature.chat

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.ChatProjectBindings
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.AgentScaffoldContent
import com.letta.mobile.feature.chat.screen.AgentScaffoldNavigationCallbacks
import com.letta.mobile.feature.chat.screen.AgentScaffoldTestTags
import com.letta.mobile.feature.chat.screen.ContextCardSession
import com.letta.mobile.feature.chat.screen.LocalSharedNavDrawer
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ContextWindowUiState
import com.letta.mobile.ui.chat.render.ProjectChatContext
import com.letta.mobile.ui.search.SearchFieldTestTag
import com.letta.mobile.ui.search.SearchTriggerTestTag
import com.letta.mobile.ui.test.setLettaTestContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android chat scaffold around the shared chat page (hard cut S9): the shared [ShellHeader]
 * with the agent pill, the message search and the menu; the shared navigation drawer; the agent
 * picker on the shared LettaPicker.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class AgentScaffoldHiltTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 640.dp))

    private val uiFlow = MutableStateFlow(
        ChatUiState(
            agentName = "IntegrationBot",
            contextWindow = ContextWindowUiState(),
        ),
    )
    private val availableAgentsFlow = MutableStateFlow(emptyList<Agent>())

    private lateinit var viewModel: AdminChatViewModel
    private lateinit var projectBindings: ChatProjectBindings
    private lateinit var conversationRepository: IConversationRepository
    private lateinit var drawer: SharedNavDrawerViewModel

    private fun conversation(id: String, summary: String): Conversation = Conversation(
        id = ConversationId(id),
        agentId = AgentId("agent-hilt-1"),
        summary = summary,
        createdAt = "2026-05-01T12:00:00Z",
        lastMessageAt = "2026-05-01T12:00:00Z",
    )

    private fun testNavigation() = AgentScaffoldNavigationCallbacks(onNavigateBack = {}, onNavigateToSettings = {})

    @Before
    fun setup() {
        viewModel = mockk(relaxed = true)
        projectBindings = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        every { viewModel.uiState } returns uiFlow
        every { viewModel.pagingPresentation } returns MutableStateFlow(null)
        every { viewModel.chatBackground } returns MutableStateFlow(com.letta.mobile.ui.theme.ChatBackground.Default)
        every { viewModel.composerState } returns MutableStateFlow(ChatComposerState())
        // No font scale yet: the chat page waits for it, so these tests see the scaffold alone.
        every { viewModel.chatFontScale } returns MutableStateFlow<Float?>(null)
        every { viewModel.availableAgents } returns availableAgentsFlow
        every { viewModel.activeAgent } returns MutableStateFlow<Agent?>(null)
        every { viewModel.conversationModelSelections } returns MutableStateFlow(com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices())
        every { viewModel.favoriteAgentId } returns MutableStateFlow<String?>(null)
        every { viewModel.pinnedAgentIds } returns MutableStateFlow(emptySet())
        every { viewModel.activeBackendLabel } returns MutableStateFlow<String?>("letta.test")
        every { viewModel.llmModels } returns MutableStateFlow(emptyList())
        every { viewModel.hapticsEnabled } returns MutableStateFlow(false)
        every { viewModel.openChatsOnCanvas } returns MutableStateFlow(false)
        every { viewModel.projectBindings } returns projectBindings
        every { viewModel.agentId } returns AgentId("agent-hilt-1")
        every { viewModel.conversationId } returns null
        every { viewModel.projectContext } returns null
        every { conversationRepository.getConversations(any<AgentId>()) } returns flowOf(emptyList())
        coEvery { conversationRepository.refreshConversations(any<AgentId>()) } returns Unit
        coEvery { conversationRepository.refreshConversationsIfStale(any<AgentId>(), any()) } returns false
        val allConversations: IAllConversationsRepository = mockk(relaxed = true) {
            every { conversations } returns MutableStateFlow(emptyList())
        }
        drawer = SharedNavDrawerViewModel(FakeSettingsRepository(), mockk(relaxed = true), allConversations, InMemorySecureSettingsStore())
    }

    /** The shared drawer's binding only where a test opens the drawer: its agent card reads the live session. */
    private fun showScaffold(withDrawer: Boolean = false) {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            CompositionLocalProvider(LocalSharedNavDrawer provides drawer.takeIf { withDrawer }) {
                AgentScaffoldContent(
                    navigation = testNavigation(),
                    conversationRepository = conversationRepository,
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun theHeaderIsTheSharedShellHeader() {
        showScaffold()
        // ShellHeader draws the pill, the one search and the menu; the scaffold's tag names it.
        val inHeader = hasAnyAncestor(hasTestTag(AgentScaffoldTestTags.HEADER))
        composeRule.onNode(hasTestTag(SearchTriggerTestTag) and inHeader).assertIsDisplayed()
        composeRule.onNode(hasTestTag(AgentScaffoldTestTags.MENU_BUTTON) and inHeader).assertIsDisplayed()
        composeRule.onNode(hasTestTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER) and inHeader).assertIsDisplayed()
    }

    @Test
    fun menuClickOpensTheSharedDrawerWithoutLoadingTheContextWindow() {
        val readings = com.letta.mobile.data.context.ContextTokenReadings()
        every { viewModel.contextCardSession } returns MutableStateFlow(
            ContextCardSession(
                readings = readings,
                breakdown = com.letta.mobile.data.context.ContextBreakdownLoader(mockk<com.letta.mobile.data.repository.api.IAgentRepository>(relaxed = true)),
                compaction = com.letta.mobile.data.compaction.CompactionController(mockk(relaxed = true), readings),
                contextLimit = com.letta.mobile.data.context.limit.ContextLimitController(mockk(relaxed = true)),
            ),
        )
        every { viewModel.modelPickerSource() } returns null
        every { viewModel.modelPicksTargetConversation() } returns false
        showScaffold(withDrawer = true)
        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        // letta-mobile-0ofhc: the drawer is no longer the context chip's trigger.
        verify(exactly = 0) { projectBindings.refreshContextWindow() }
        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_CONTENT).assertIsDisplayed()
    }

    @Test
    fun headerSearchForwardsTheQueryToTheMessageSearch() {
        showScaffold()
        composeRule.onNodeWithTag(SearchTriggerTestTag).performClick()
        composeRule.onNodeWithTag(SearchFieldTestTag).performTextInput("needle")
        verify { viewModel.updateChatSearchQuery("needle") }
    }

    @Test
    fun headerSearchListsMessageHitsWithTheirConversation() {
        every { viewModel.conversationId } returns ConversationId("conv-current")
        every { conversationRepository.getConversations(any<AgentId>()) } returns flowOf(
            listOf(
                conversation("conv-current", "Current planning"),
                conversation("conv-previous", "Previous planning"),
            ),
        )
        uiFlow.value = ChatUiState(
            agentName = "IntegrationBot",
            contextWindow = ContextWindowUiState(),
            isSearchActive = true,
            searchQuery = "needle",
            searchResults = persistentListOf(
                ParsedSearchMessage(
                    messageId = "msg-previous",
                    agentId = "agent-hilt-1",
                    role = "assistant",
                    content = "Needle appears in an older thread",
                    date = "2026-05-01T12:00:00Z",
                    conversationId = "conv-previous",
                ),
            ),
        )
        showScaffold()
        composeRule.onNodeWithTag(SearchTriggerTestTag).performClick()
        composeRule.onNodeWithText("Needle appears in an older thread").assertIsDisplayed()
        composeRule.onNode(hasText("Previous conversation", substring = true)).assertIsDisplayed()
    }

    @Test
    fun agentPickerFiltersByVisibleAgentMetadata() {
        availableAgentsFlow.value = listOf(
            Agent(
                id = AgentId("agent-alpha"),
                name = "Alpha Agent",
                description = "Roadmap helper",
                model = "openai/gpt-5-mini",
            ),
            Agent(
                id = AgentId("agent-beta"),
                name = "Beta Specialist",
                description = "Release triage",
                model = "anthropic/claude-3-5-sonnet",
            ),
        )
        showScaffold()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER).performClick()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.AGENT_PICKER_SEARCH_FIELD).performTextInput("triage")

        composeRule.onNodeWithText("Beta Specialist").assertIsDisplayed()
        composeRule.onAllNodesWithText("Alpha Agent").assertCountEquals(0)
    }

    @Test
    @Ignore("Project-context composition in this Hilt harness is flaky; covered by dedicated project context card tests")
    fun projectContextRendersProjectSurfacesAndBugFab() {
        every { viewModel.projectContext } returns ProjectChatContext(identifier = "proj-1", name = "Project One")
        showScaffold()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT).assertIsDisplayed()
    }

    @Test
    fun noProjectContextHidesProjectBugFab() {
        every { viewModel.projectContext } returns null
        showScaffold()
        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertCountEquals(0)
    }
}
