package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.ui.test.setLettaTestContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class ConversationPickerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun conversation(id: String, summary: String): Conversation = Conversation(
        id = id,
        agentId = "agent-1",
        summary = summary,
        createdAt = "2026-05-01T12:00:00Z",
        lastMessageAt = "2026-05-01T12:00:00Z",
    )

    @Test
    fun openingPickerShowsRepositoryConversations() {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val conversations = MutableStateFlow(listOf(
            conversation("conv-1", "First conversation"),
            conversation("conv-2", "Second conversation"),
        ))
        coEvery { repo.refreshConversations(any()) } returns Unit
        every { repo.getConversations("agent-1") } returns conversations
        val vm = ConversationPickerViewModel(repo)

        composeRule.setLettaTestContent {
            ConversationPickerSheet(
                agentId = "agent-1",
                currentConversationId = null,
                onDismiss = {},
                onConversationSelected = {},
                onNewConversation = {},
                viewModel = vm,
            )
        }

        composeRule.onNodeWithText("Conversations").assertIsDisplayed()
        composeRule.onNodeWithText("First conversation").assertIsDisplayed()
        composeRule.onAllNodesWithText("Second conversation").assertCountEquals(1)
    }

    @Test
    fun tappingNewConversationInvokesNewAction() {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val conversations = MutableStateFlow(emptyList<Conversation>())
        coEvery { repo.refreshConversations(any()) } returns Unit
        every { repo.getConversations("agent-1") } returns conversations
        val vm = ConversationPickerViewModel(repo)
        var newConversationId: String? = "not-called"

        composeRule.setLettaTestContent {
            ConversationPickerSheet(
                agentId = "agent-1",
                currentConversationId = null,
                onDismiss = {},
                onConversationSelected = {},
                onNewConversation = { newConversationId = it.conversationId },
                viewModel = vm,
            )
        }

        composeRule.onNodeWithText("New Conversation").performClick()
        composeRule.waitUntil(5_000) { newConversationId == null }
    }

    @Test
    fun tappingExistingConversationInvokesSelectionAction() {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val conversations = MutableStateFlow(listOf(conversation("conv-1", "Pick me")))
        coEvery { repo.refreshConversations(any()) } returns Unit
        every { repo.getConversations("agent-1") } returns conversations
        val vm = ConversationPickerViewModel(repo)
        var selectedConversationId: String? = null

        composeRule.setLettaTestContent {
            ConversationPickerSheet(
                agentId = "agent-1",
                currentConversationId = null,
                onDismiss = {},
                onConversationSelected = { selectedConversationId = it.conversationId },
                onNewConversation = {},
                viewModel = vm,
            )
        }

        composeRule.onNodeWithText("Pick me").performClick()
        composeRule.waitUntil(5_000) { selectedConversationId == "conv-1" }
    }

    @Test
    fun longPressEntersSelectionModeAndShowsDeleteAction() {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val conversations = MutableStateFlow(listOf(conversation("conv-1", "Selectable")))
        coEvery { repo.refreshConversations(any()) } returns Unit
        every { repo.getConversations("agent-1") } returns conversations
        val vm = ConversationPickerViewModel(repo)

        composeRule.setLettaTestContent {
            ConversationPickerSheet(
                agentId = "agent-1",
                currentConversationId = null,
                onDismiss = {},
                onConversationSelected = {},
                onNewConversation = {},
                viewModel = vm,
            )
        }

        composeRule.onNodeWithText("Selectable").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
    }

    @Test
    fun confirmingDeleteForActiveConversationRoutesToNewConversation() {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val conversations = MutableStateFlow(listOf(conversation("conv-active", "Active")))
        coEvery { repo.refreshConversations(any()) } returns Unit
        every { repo.getConversations("agent-1") } returns conversations
        coEvery { repo.deleteConversation(any(), any()) } returns Unit
        val vm = ConversationPickerViewModel(repo)
        var newConversationId: String? = "not-called"

        composeRule.setLettaTestContent {
            ConversationPickerSheet(
                agentId = "agent-1",
                currentConversationId = "conv-active",
                onDismiss = {},
                onConversationSelected = {},
                onNewConversation = { newConversationId = it.conversationId },
                viewModel = vm,
            )
        }

        composeRule.onNodeWithText("Active").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(5_000) { newConversationId == null }
    }
}
