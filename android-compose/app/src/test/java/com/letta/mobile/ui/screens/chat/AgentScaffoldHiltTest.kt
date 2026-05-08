package com.letta.mobile.ui.screens.chat

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.test.setLettaTestContent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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

    private lateinit var viewModel: AdminChatViewModel

    @Before
    fun setup() {
        hiltRule.inject()

        viewModel = mockk(relaxed = true)
        every { viewModel.uiState } returns uiFlow
        every { viewModel.chatBackground } returns bgFlow
        every { viewModel.composerState } returns composerFlow
        every { viewModel.chatFontScale } returns fontScaleFlow
        every { viewModel.agentId } returns "agent-hilt-1"
        every { viewModel.conversationId } returns null
        every { viewModel.projectContext } returns null
    }

    @Test
    fun drawerOpensWhenMenuIconTapped() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
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
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        composeRule.onNodeWithText("Edit Agent").performClick()
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
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        composeRule.onNodeWithText("Reset Messages").performClick()
        verify(exactly = 1) { viewModel.resetMessages() }
    }

    @Test
    fun menuClickRefreshesContextWindowAndOpensDrawer() {
        composeRule.setLettaTestContent(windowSizeClass = windowSizeClass) {
            AgentScaffold(
                onNavigateBack = {},
                onNavigateToSettings = {},
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.MENU_BUTTON).performClick()
        verify(exactly = 1) { viewModel.refreshContextWindow() }
        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_CONTENT).assertIsDisplayed()
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
                viewModel = viewModel,
            )
        }

        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.PROJECT_BUG_FAB).assertCountEquals(0)
    }

}
