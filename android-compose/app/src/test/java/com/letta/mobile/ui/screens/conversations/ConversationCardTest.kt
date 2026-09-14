package com.letta.mobile.ui.screens.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.test.setLettaTestContent
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConversationCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `ConversationCard displays title, pinned status, and agent name`() {
        val conversation = TestData.conversation(
            id = "conv-1",
            agentId = "agent-1",
            summary = "My Conversation"
        ).copy(createdAt = Instant.now().toString())

        val display = ConversationDisplay(
            conversation = conversation,
            agentName = "Test Agent",
            isPinned = true
        )

        composeTestRule.setLettaTestContent {
                ConversationCard(
                    display = display,
                    callbacks = ConversationCardCallbacks(
                        onClick = {},
                        onOpenAdmin = {},
                        onDelete = {},
                        onRename = {},
                        onTogglePinned = {},
                        onFork = {},
                        onArchiveToggle = {},
                    ),
                )
        }

        composeTestRule.onNodeWithText("My Conversation").assertIsDisplayed()
        // Status, bullet, and agent name are separate Text nodes in ConversationCardStatusRow.
        composeTestRule.onNodeWithText("Pinned").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Agent").assertIsDisplayed()
    }

    @Test
    fun `ConversationCard displays agent name without pinned status`() {
        val conversation = TestData.conversation(
            id = "conv-1",
            agentId = "agent-1",
            summary = "My Conversation"
        ).copy(createdAt = Instant.now().toString())

        val display = ConversationDisplay(
            conversation = conversation,
            agentName = "Test Agent",
            isPinned = false
        )

        composeTestRule.setLettaTestContent {
                ConversationCard(
                    display = display,
                    callbacks = ConversationCardCallbacks(
                        onClick = {},
                        onOpenAdmin = {},
                        onDelete = {},
                        onRename = {},
                        onTogglePinned = {},
                        onFork = {},
                        onArchiveToggle = {},
                    ),
                )
        }

        composeTestRule.onNodeWithText("My Conversation").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pinned").assertDoesNotExist()
    }
}
