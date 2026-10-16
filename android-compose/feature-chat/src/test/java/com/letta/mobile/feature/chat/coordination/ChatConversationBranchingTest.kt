package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.branch.BranchHistory
import com.letta.mobile.data.chat.branch.BranchMessage
import com.letta.mobile.data.chat.branch.PendingComposerDrafts
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.ui.chat.session.ChatBranchView
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-7mys7 / bzvro.32: on Android's paged timeline the page's message list is empty, so a
 * branch places its fork point on the server's stored messages, and a rerun hands its prompt to
 * the fork's chat to send at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatConversationBranchingTest {
    private val stored = BranchHistory(
        listOf(
            BranchMessage("message-1", isPrompt = true, text = "first"),
            BranchMessage("message-2", isPrompt = false, text = "answer"),
            BranchMessage("message-3", isPrompt = true, text = "second"),
        ),
        complete = true,
    )
    private val repository: IConversationRepository = mockk {
        coEvery { forkConversation(any<ConversationId>(), any<AgentId>(), any()) } returns
            Conversation(id = ConversationId("fork-1"), agentId = AgentId("agent-1"))
    }
    private val prompt = UiMessage(id = "message-3", role = "user", content = "second", timestamp = "t")

    @Test
    fun `an edit on the paged timeline forks from the server's list`() = runTest(UnconfinedTestDispatcher()) {
        val drafts = PendingComposerDrafts()
        val opened = mutableListOf<String>()
        val branching = ChatConversationBranching(this, repository, onError = {}, history = { stored }, drafts = drafts)

        branching.page(opened::add).editAndResend(prompt, view)

        coVerify { repository.forkConversation(ConversationId("conv-1"), AgentId("agent-1"), "message-2") }
        assertEquals(listOf("fork-1"), opened)
        val pending = drafts.take("fork-1")
        assertEquals("second", pending?.draft?.text)
        assertEquals(false, pending?.sendNow)
    }

    @Test
    fun `a rerun leaves its prompt to be sent as soon as the fork opens`() = runTest(UnconfinedTestDispatcher()) {
        val drafts = PendingComposerDrafts()
        val branching = ChatConversationBranching(this, repository, onError = {}, history = { stored }, drafts = drafts)

        branching.page {}.rerun(prompt, view)

        assertTrue(drafts.take("fork-1")?.sendNow == true)
    }

    @Test
    fun `a failure reaches the page`() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<String>()
        coEvery { repository.forkConversation(any<ConversationId>(), any<AgentId>(), any()) } throws IllegalStateException("host offline")
        val branching = ChatConversationBranching(this, repository, onError = { errors += it }, history = { stored })

        branching.page {}.forkFrom(prompt, view)

        assertEquals(listOf("host offline"), errors)
    }

    private val view = ChatBranchView(emptyList(), hasOlderMessages = false)

    private fun ChatConversationBranching.page(onOpen: (String) -> Unit) =
        Page { view -> ChatConversationBranching.Origin("conv-1", "agent-1", view, open = { _, conversationId -> onOpen(conversationId) }) }
}
