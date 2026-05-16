package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.letta.mobile.data.model.Conversation
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

    private fun conversation(id: String, summary: String): Conversation = Conversation(
        id = id,
        agentId = "agent-drawer-42",
        summary = summary,
        createdAt = "2026-05-01T12:00:00Z",
        lastMessageAt = "2026-05-01T12:00:00Z",
    )

    @Test
    fun drawerContentRendersAgentNameAndBackendLabel() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "DrawerBot 42",
                agentId = "agent-drawer-42",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("DrawerBot 42").assertIsDisplayed()
        composeRule.onNodeWithText("letta.test").assertIsDisplayed()
    }

    @Test
    fun drawerEditAgentFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "EditBot",
                agentId = "agent-edit-1",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = { fired = true },
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithTag(AgentScaffoldTestTags.DRAWER_EDIT_AGENT).performClick()
        assertTrue("Edit callback should fire", fired)
    }

    @Test
    fun drawerDoesNotRenderArchivalOrToolsEntries() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "LeanDrawerBot",
                agentId = "agent-lean-1",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onAllNodesWithText("Archival Memory").assertCountEquals(0)
        composeRule.onAllNodesWithText("Tools").assertCountEquals(0)
    }

    @Test
    fun drawerRendersConversationListWithoutRemovedNavigationEntries() {
        var selectedConversationId: String? = null
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "DrawerBot 42",
                agentId = "agent-drawer-42",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = listOf(
                    conversation("conv-alpha", "Alpha planning"),
                    conversation("conv-beta", "Beta follow-up"),
                ),
                currentConversationId = "conv-alpha",
                onNewConversation = {},
                onConversationSelected = { selectedConversationId = it },
                onEditAgent = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Alpha planning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Beta follow-up").performScrollTo().performClick()
        assertTrue("Conversation callback should receive selected id", selectedConversationId == "conv-beta")
        composeRule.onAllNodesWithText("Archival Memory").assertCountEquals(0)
        composeRule.onAllNodesWithText("Tools").assertCountEquals(0)
    }

    @Test
    fun drawerChatModeFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "ModeBot",
                agentId = "agent-mode-1",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = { if (it == "debug") fired = true },
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Debug").performClick()
        assertTrue("Debug mode callback should fire", fired)
    }

    @Test
    fun drawerResetMessagesFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "ResetBot",
                agentId = "agent-reset-1",
                activeBackendLabel = "letta.test",
                contextWindow = emptyContextWindow(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
                onResetMessages = { fired = true },
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Reset Messages").performScrollTo().performClick()
        assertTrue("Reset callback should fire", fired)
    }

    @Test
    fun drawerContextWindowRefreshFiresCallback() {
        var fired = false
        composeRule.setLettaTestContent(useChatTheme = false) {
            DrawerContent(
                agentName = "CtxBot",
                agentId = "agent-ctx-1",
                activeBackendLabel = "letta.test",
                contextWindow = ContextWindowUiState(maxTokens = 1000, currentTokens = 300),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
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
                activeBackendLabel = "letta.test",
                contextWindow = ContextWindowUiState(
                    maxTokens = 1000,
                    currentTokens = 300,
                ),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
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
                activeBackendLabel = "letta.test",
                contextWindow = ContextWindowUiState(),
                chatMode = "interactive",
                onChatModeSelected = {},
                conversations = emptyList(),
                currentConversationId = null,
                onNewConversation = {},
                onConversationSelected = {},
                onEditAgent = {},
                onResetMessages = {},
                onRefreshContextWindow = {},
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Context usage is not available yet.").assertIsDisplayed()
    }
}
