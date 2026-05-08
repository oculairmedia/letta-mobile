package com.letta.mobile.ui.screens.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class AgentScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun emptyContextWindow() = ContextWindowUiState()

    @Test
    fun drawerContentRendersAgentNameAndMessageCount() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "DrawerBot 42",
                agentId = "agent-drawer-42",
                messageCount = 99,
                contextWindow = emptyContextWindow(),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("DrawerBot 42").assertIsDisplayed()
        composeRule.onNodeWithText("99 messages").assertIsDisplayed()
    }

    @Test
    fun drawerEditAgentFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "EditBot",
                agentId = "agent-edit-1",
                messageCount = 1,
                contextWindow = emptyContextWindow(),
                onEditAgent = { fired = true },
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Edit Agent").performClick()
        assertTrue("Edit callback should fire", fired)
    }

    @Test
    fun drawerArchivalMemoryFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "ArchBot",
                agentId = "agent-arch-1",
                messageCount = 1,
                contextWindow = emptyContextWindow(),
                onEditAgent = {},
                onArchivalMemory = { fired = true },
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Archival Memory").performClick()
        assertTrue("Archival callback should fire", fired)
    }

    @Test
    fun drawerToolsFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "ToolBot",
                agentId = "agent-tool-1",
                messageCount = 1,
                contextWindow = emptyContextWindow(),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = { fired = true },
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Tools").performClick()
        assertTrue("Tools callback should fire", fired)
    }

    @Test
    fun drawerResetMessagesFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "ResetBot",
                agentId = "agent-reset-1",
                messageCount = 1,
                contextWindow = emptyContextWindow(),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = { fired = true },
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Reset Messages").performClick()
        assertTrue("Reset callback should fire", fired)
    }

    @Test
    fun drawerContextWindowRefreshFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "CtxBot",
                agentId = "agent-ctx-1",
                messageCount = 1,
                contextWindow = ContextWindowUiState(maxTokens = 1000, currentTokens = 300),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = { fired = true },
                onClose = {},
            )
        }

        composeRule.onNodeWithContentDescription("Refresh").performClick()
        assertTrue("Context window refresh callback should fire", fired)
    }

    @Test
    fun drawerContextWindowShowsUsageWhenTokensAvailable() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "CtxDisplay",
                agentId = "agent-ctx-display",
                messageCount = 0,
                contextWindow = ContextWindowUiState(
                    maxTokens = 1000,
                    currentTokens = 300,
                    messageCount = 5,
                ),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Context utilization").assertIsDisplayed()
    }

    @Test
    fun drawerContextWindowShowsUnavailableWhenNoTokens() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "NoCtxBot",
                agentId = "agent-noctx",
                messageCount = 0,
                contextWindow = ContextWindowUiState(),
                onEditAgent = {},
                onArchivalMemory = {},
                onTools = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Context usage is not available yet.").assertIsDisplayed()
    }
}
