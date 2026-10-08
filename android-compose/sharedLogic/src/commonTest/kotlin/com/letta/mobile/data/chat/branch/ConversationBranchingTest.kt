package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.15 / .16: where a fork or an edit branches, and what reaches the backend. */
class ConversationBranchingTest {
    private val timeline = listOf(
        prompt("m1", "first question"),
        reply("m2", "first answer"),
        prompt("m3", "second question"),
        reasoning("m4"),
        reply("m5", "second answer"),
        prompt("m6", "third question"),
    )

    @Test
    fun forkingAPromptKeepsTheReplyThatAnsweredIt() {
        assertEquals(BranchPoint.Through("m5"), ConversationBranchPlanner.forkPoint(timeline.map(BranchMessage::of), BranchMessage.of(timeline[2])))
    }

    @Test
    fun forkingAnUnansweredPromptGoesThroughThePromptItself() {
        assertEquals(BranchPoint.Through("m6"), ConversationBranchPlanner.forkPoint(timeline.map(BranchMessage::of), BranchMessage.of(timeline[5])))
    }

    @Test
    fun forkingAReplyGoesThroughTheReply() {
        assertEquals(BranchPoint.Through("m2"), ConversationBranchPlanner.forkPoint(timeline.map(BranchMessage::of), BranchMessage.of(timeline[1])))
    }

    @Test
    fun editingAPromptForksThroughTheMessageBeforeIt() {
        assertEquals(BranchPoint.Through("m5"), ConversationBranchPlanner.editPoint(timeline.map(BranchMessage::of), BranchMessage.of(timeline[5])))
        assertEquals(BranchPoint.Through("m2"), ConversationBranchPlanner.editPoint(timeline.map(BranchMessage::of), BranchMessage.of(timeline[2])))
    }

    @Test
    fun editingTheFirstPromptStartsAnEmptyConversationOnlyWhenHistoryIsComplete() {
        val messages = timeline.map(BranchMessage::of)
        assertEquals(BranchPoint.Empty, ConversationBranchPlanner.editPoint(messages, BranchMessage.of(timeline[0])))
        assertNull(ConversationBranchPlanner.editPoint(messages, BranchMessage.of(timeline[0]), historyComplete = false))
    }

    @Test
    fun unsavedRowsAreNeverAForkPoint() {
        val withPending = listOf(prompt("m1", "q"), reply("m2", "a").copy(isError = true), prompt("m3", "q2"))
        assertEquals(BranchPoint.Through("m1"), ConversationBranchPlanner.editPoint(withPending.map(BranchMessage::of), BranchMessage.of(withPending[2])))
        assertTrue(!ConversationBranchPlanner.canFork(prompt("local-1", "q").copy(isPending = true)))
        assertTrue(!ConversationBranchPlanner.canEdit(prompt("m9", "q").copy(isSendFailed = true)))
        assertTrue(!ConversationBranchPlanner.canFork(reasoning("m4")))
    }

    @Test
    fun aPromptKnownByItsClientIdMatchesTheServerCopy() {
        val server = listOf(BranchMessage("message-1", isPrompt = true, alias = "otid-1"), BranchMessage("message-2", isPrompt = false))
        val onPage = prompt("otid-1", "hello")
        assertEquals(BranchPoint.Through("message-2"), ConversationBranchPlanner.forkPoint(server, BranchMessage.of(onPage)))
    }

    @Test
    fun forkSendsTheConversationAgentAndMessageAndNeverTouchesTheSource() = runTest {
        val backend = RecordingBackend()
        val fork = ConversationBranching(backend.asBackend()).forkFrom(BranchSource("conv-1", "agent-1", timeline), timeline[2])

        assertEquals("fork-1", fork.id.value)
        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-1", "m5")), backend.forks)
        assertEquals(emptyList(), backend.created)
    }

    @Test
    fun editForksBeforeThePromptAndReturnsItsTextAsTheDraft() = runTest {
        val backend = RecordingBackend()
        val edit = ConversationBranching(backend.asBackend()).editAndResend(BranchSource("conv-1", "agent-1", timeline), timeline[5])

        assertEquals("third question", edit.draft)
        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-1", "m5")), backend.forks)
    }

    @Test
    fun editOfTheOpeningPromptCreatesAnEmptyConversationInsteadOfForking() = runTest {
        val backend = RecordingBackend()
        val edit = ConversationBranching(backend.asBackend()).editAndResend(BranchSource("conv-1", "agent-1", timeline), timeline[0])

        assertEquals("first question", edit.draft)
        assertEquals(emptyList(), backend.forks)
        assertEquals(listOf("agent-1"), backend.created)
    }

    @Test
    fun aMessageThePageDoesNotHoldIsPlacedFromTheServerHistory() = runTest {
        val backend = RecordingBackend(
            history = BranchHistory(
                listOf(BranchMessage("m1", true), BranchMessage("m2", false), BranchMessage("m3", true)),
                complete = true,
            ),
        )
        val edit = ConversationBranching(backend.asBackend()).editAndResend(BranchSource("conv-1", "agent-1", emptyList()), prompt("m3", "q"))

        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-1", "m2")), backend.forks)
        assertEquals("q", edit.draft)
    }

    @Test
    fun anEditWithoutAKnownPredecessorAsksForOlderMessages() = runTest {
        val backend = RecordingBackend()
        val failure = assertFailsWith<BranchUnavailableException> {
            ConversationBranching(backend.asBackend())
                .editAndResend(BranchSource("conv-1", "agent-1", timeline, historyComplete = false), timeline[0])
        }
        assertEquals(ConversationBranching.LOAD_OLDER, failure.message)
        assertEquals(emptyList(), backend.forks)
    }

    @Test
    fun anUnsavedMessageCannotBranch() = runTest {
        val backend = RecordingBackend()
        assertFailsWith<BranchUnavailableException> {
            ConversationBranching(backend.asBackend()).forkFrom(BranchSource("conv-1", "agent-1", timeline), prompt("local", "q").copy(isPending = true))
        }
        assertEquals(emptyList(), backend.forks)
    }

    private class RecordingBackend(private val history: BranchHistory? = null) {
        val forks = mutableListOf<ConversationForkRequest>()
        val created = mutableListOf<String>()

        fun asBackend() = BranchBackend(
            fork = { request -> forks += request; conversation("fork-${forks.size}") },
            createEmpty = { agentId -> created += agentId; conversation("empty-${created.size}") },
            history = history?.let { h -> { _ -> h } },
        )
    }

    companion object {
        fun conversation(id: String) = Conversation(id = ConversationId(id), agentId = AgentId("agent-1"))

        fun prompt(id: String, text: String) = UiMessage(id = id, role = "user", content = text, timestamp = "t")

        fun reply(id: String, text: String) = UiMessage(id = id, role = "assistant", content = text, timestamp = "t")

        fun reasoning(id: String) = UiMessage(id = id, role = "assistant", content = "thinking", timestamp = "t", isReasoning = true)
    }
}
