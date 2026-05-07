package com.letta.mobile.ui.screens.chat

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
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
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                LettaTheme(
                    appTheme = AppTheme.LIGHT,
                    themePreset = ThemePreset.DEFAULT,
                    dynamicColor = false,
                ) {
                    LettaChatTheme {
                        AgentScaffold(
                            onNavigateBack = {},
                            onNavigateToSettings = {},
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Context utilization").assertIsDisplayed()
    }

    @Test
    fun drawerEditAgentFiresCallback() {
        var settingsCalledWith = ""
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                LettaTheme(
                    appTheme = AppTheme.LIGHT,
                    themePreset = ThemePreset.DEFAULT,
                    dynamicColor = false,
                ) {
                    LettaChatTheme {
                        AgentScaffold(
                            onNavigateBack = {},
                            onNavigateToSettings = { settingsCalledWith = it },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Edit Agent").performClick()
        assert(settingsCalledWith == "agent-hilt-1") {
            "Expected onNavigateToSettings with agent-hilt-1, got: $settingsCalledWith"
        }
    }

    @Test
    fun drawerResetMessagesCallsViewModel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                LettaTheme(
                    appTheme = AppTheme.LIGHT,
                    themePreset = ThemePreset.DEFAULT,
                    dynamicColor = false,
                ) {
                    LettaChatTheme {
                        AgentScaffold(
                            onNavigateBack = {},
                            onNavigateToSettings = {},
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Reset Messages").performClick()
        verify(exactly = 1) { viewModel.resetMessages() }
    }
}
