package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.AgentId
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.ui.test.setLettaTestContent
import com.letta.mobile.data.repository.ConversationRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.collections.immutable.persistentListOf
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = dagger.hilt.android.testing.HiltTestApplication::class, sdk = [34])
@Tag("integration")
class AgentScaffoldHiltTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 640.dp))

    private val uiFlow = MutableStateFlow(
        ChatUiState(
            agentName = "IntegrationBot",
            contextWindow = ContextWindowUiState(),
        )
    )
    private val bgFlow = MutableStateFlow(
        com.letta.mobile.ui.theme.ChatBackground.Default
    )
    private val composerFlow = MutableStateFlow(ChatComposerState())
    private val fontScaleFlow = MutableStateFlow(1.0f)
    private val availableAgentsFlow = MutableStateFlow(emptyList<com.letta.mobile.data.model.Agent>())

    private lateinit var viewModel: AdminChatViewModel
    private lateinit var conversationRepository: ConversationRepository

    private fun conversation(id: String, summary: String): Conversation = Conversation(
        id = id,
        agentId = "agent-hilt-1",
        summary = summary,
        createdAt = "2026-05-01T12:00:00Z",
        lastMessageAt = "2026-05-01T12:00:00Z",
    )

    @Before
    fun setup() {
        hiltRule.inject()

        viewModel = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        every { viewModel.uiState } returns uiFlow
        every { viewModel.chatBackground } returns bgFlow
        every { viewModel.composerState } returns composerFlow
        every { viewModel.chatFontScale } returns fontScaleFlow
        every { viewModel.availableAgents } returns availableAgentsFlow
        every { viewModel.favoriteAgentId } returns MutableStateFlow<String?>(null)
        every { viewModel.pinnedAgentIds } returns MutableStateFlow(emptySet())
        every { viewModel.agentId } returns "agent-hilt-1"
        every { viewModel.conversationId } returns null
        every { viewModel.projectContext } returns null
        every { conversationRepository.getConversations(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.refreshConversations(any()) } returns Unit
    }

    @Test
    fun drawerOpensWhenMenuIconTapped() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        composeRule.onNodeWithText("Context utilization").assertIsDisplayed()
    }

    @Test
    fun drawerEditAgentFiresCallback() {
        var settingsCalledWith = ""
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = { settingsCalledWith = it },
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_EDIT_AGENT).performClick()
        assert(settingsCalledWith == "agent-hilt-1") {
            "Expected onNavigateToSettings with agent-hilt-1, got: $settingsCalledWith"
        }
    }

    @Test
    fun drawerResetMessagesCallsViewModel() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        composeRule.onNodeWithText("Reset Messages").performScrollTo().performClick()
        verify(exactly = 1) { viewModel.resetMessages() }
    }

    @Test
    fun menuClickRefreshesContextWindowAndOpensDrawer() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        verify(exactly = 1) { viewModel.refreshContextWindow() }
        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_CONTENT).assertIsDisplayed()
    }

    @Test
    fun searchActionShowsGlobalSearchFieldAndForwardsQuery() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Search conversations…").assertIsDisplayed()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.CHAT_SEARCH_FIELD).performTextInput("needle")

        verify { viewModel.updateChatSearchQuery("needle") }
    }

    @Test
    fun searchResultsLabelPreviousConversationMatches() {
        every { viewModel.conversationId } returns "conv-current"
        every { conversationRepository.getConversations(any()) } returns flowOf(
            listOf(
                conversation("conv-current", "Current planning"),
                conversation("conv-previous", "Previous planning"),
            )
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
                )
            ),
        )

        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithText("Messages").assertIsDisplayed()
        composeRule.onNodeWithText("Previous conversation").assertIsDisplayed()
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

        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER).performClick()
        composeRule.onNodeWithText("Search agents…").assertIsDisplayed()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.AGENT_PICKER_SEARCH_FIELD).performTextInput("beta")

        composeRule.onNodeWithText("Beta Specialist").assertIsDisplayed()
        composeRule.onAllNodesWithText("Alpha Agent").assertCountEquals(0)
    }

    @Test
    @Ignore("Project-context composition in this Hilt harness is flaky; covered by dedicated project context card tests")
    fun projectContextRendersProjectSurfacesAndBugFab() {
        every { viewModel.projectContext } returns ProjectChatContext(
            identifier = "proj-1",
            name = "Project One",
        )

        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentScaffoldTestTags.CHAT_SCREEN_CONTENT).assertIsDisplayed()
    }

    @Test
    fun noProjectContextHidesProjectBugFab() {
        every { viewModel.projectContext } returns null

        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                conversationRepository = conversationRepository,
                viewModel = viewModel,
            )
        }

        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertCountEquals(0)
    }

}
