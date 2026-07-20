package com.letta.mobile.ui.screens.conversations

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.testutil.TestData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.ui.test.setLettaTestContent
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConversationCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `ConversationCard displays title and condensed metadata correctly`() {
        // Use a time very close to now so formatRelativeTime returns "just now" or similar,
        // but just checking prefix is safer.
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
                    ),
                )
        }

        // Check if the title exists
        composeTestRule.onNodeWithText("My Conversation").assertIsDisplayed()

        // Check if the condensed metadata string contains the Pinned text and Agent Name
        composeTestRule.onNodeWithText("Pinned • Test Agent", substring = true).assertIsDisplayed()
    }

    @Test
    fun `ConversationCard displays condensed metadata without pinned`() {
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
                    ),
                )
        }

        // The prefix should just be "Test Agent" with no "Pinned • "
        composeTestRule.onNodeWithText("Test Agent • ", substring = true).assertIsDisplayed()
    }
}
