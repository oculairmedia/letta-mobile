package com.letta.mobile.feature.chat

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.feature.chat.coordination.ChatComposerState
import com.letta.mobile.feature.chat.coordination.ChatProjectBindings
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.AgentScaffoldContent
import com.letta.mobile.feature.chat.screen.AgentScaffoldNavigationCallbacks
import com.letta.mobile.feature.chat.screen.AgentScaffoldTestTags
import com.letta.mobile.feature.chat.screen.SharedNavDrawerViewModel
import com.letta.mobile.feature.chat.screen.WorkspaceChatHost
import com.letta.mobile.feature.chat.screen.WorkspaceChatPanel
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ContextWindowUiState
import com.letta.mobile.ui.search.SearchTriggerTestTag
import com.letta.mobile.ui.test.setLettaTestContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android chat inside the shared shell workspace (letta-mobile-ckmub.2): the workspace draws the
 * header, rail and agent panel, so the chat draws none of them; it reports the conversation it settled
 * on, takes a draft the shell routed to it once, and puts its card and Chat options in the panel.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class AgentScaffoldHiltTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 640.dp))

    private val uiFlow = MutableStateFlow(ChatUiState(agentName = "IntegrationBot", contextWindow = ContextWindowUiState()))

    private lateinit var viewModel: AdminChatViewModel
    private lateinit var projectBindings: ChatProjectBindings
    private lateinit var conversationRepository: IConversationRepository
    private lateinit var drawer: SharedNavDrawerViewModel

    private val shown = mutableListOf<Pair<String, String>>()
    private val panel = mutableStateOf<WorkspaceChatPanel?>(null)
    private val draftState = mutableStateOf<String?>(null)
    private val opened = mutableListOf<ShellDestination>()

    private fun host(draft: String? = null) = WorkspaceChatHost(
        open = { opened += it },
        openConversation = { _, _ -> },
        openAgentPane = {},
        openCanvas = { _, _, _ -> },
        onConversationShown = { agent, conversation -> shown += agent to conversation },
        draft = draftState.apply { value = draft },
        openSearch = {},
        drawer = drawer,
        panel = panel,
    )

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
        every { viewModel.availableAgents } returns MutableStateFlow(emptyList<Agent>())
        every { viewModel.activeAgent } returns MutableStateFlow<Agent?>(null)
        every { viewModel.conversationModelSelections } returns MutableStateFlow(com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices())
        every { viewModel.favoriteAgentId } returns MutableStateFlow<String?>(null)
        every { viewModel.pinnedAgentIds } returns MutableStateFlow(emptySet())
        every { viewModel.llmModels } returns MutableStateFlow(emptyList())
        every { viewModel.hapticsEnabled } returns MutableStateFlow(false)
        every { viewModel.openChatsOnCanvas } returns MutableStateFlow(false)
        every { viewModel.projectBindings } returns projectBindings
        every { viewModel.agentId } returns AgentId("agent-hilt-1")
        every { viewModel.conversationId } returns null
        every { viewModel.projectContext } returns null
        every { conversationRepository.getConversations(any<AgentId>()) } returns flowOf(emptyList())
        coEvery { conversationRepository.refreshConversationsIfStale(any<AgentId>(), any()) } returns false
        val allConversations: IAllConversationsRepository = mockk(relaxed = true) {
            every { conversations } returns MutableStateFlow(emptyList())
        }
        val settings = InMemorySecureSettingsStore()
        drawer = SharedNavDrawerViewModel(
            FakeSettingsRepository(), mockk(relaxed = true), allConversations, settings,
            sources = mockk(relaxed = true) { every { conversations } returns conversationRepository },
        )
    }

    private fun showChat(host: WorkspaceChatHost = host()) {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffoldContent(
                navigation = AgentScaffoldNavigationCallbacks(onNavigateBack = {}, onNavigateToSettings = {}),
                viewModel = viewModel,
                workspace = host,
            )
        }
    }

    @Test
    fun theChatDrawsNoHeaderOfItsOwn() {
        showChat()
        composeRule.onAllNodesWithTag(SearchTriggerTestTag).assertCountEquals(0)
    }

    @Test
    fun theChatReportsTheConversationItSettledOn() {
        every { viewModel.conversationId } returns ConversationId("conv-1")
        showChat()
        composeRule.waitForIdle()
        assertEquals(listOf("agent-hilt-1" to "conv-1"), shown)
    }

    @Test
    fun aDraftGoesToTheComposerOnce() {
        showChat(host(draft = "hello"))
        composeRule.waitForIdle()
        verify(exactly = 1) { viewModel.handleComposerTextChanged("hello") }
        assertEquals(null, draftState.value)
    }

    @Test
    fun theChatPutsItsCardAndChatOptionsInTheAgentPanel() {
        showChat()
        composeRule.waitForIdle()
        assertNotNull(panel.value)
    }

    @Test
    fun noProjectContextHidesProjectBugFab() {
        showChat()
        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertCountEquals(0)
    }
}
