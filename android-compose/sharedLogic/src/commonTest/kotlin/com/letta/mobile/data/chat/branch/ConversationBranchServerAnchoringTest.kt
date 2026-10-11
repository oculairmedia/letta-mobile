package com.letta.mobile.data.chat.branch

import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.conversation
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.prompt
import com.letta.mobile.data.chat.branch.ConversationBranchingTest.Companion.reply
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * letta-mobile-7mys7: a fork point is a projected server id. letta-code's `conversation_fork` answers
 * "Message not found" for anything else, and a timeline row can carry a streamed (`cm-stream-…`),
 * reasoning (`…:REASONING`) or optimistic (the otid) id, so the server's own list decides.
 */
class ConversationBranchServerAnchoringTest {
    private val stored = listOf(
        BranchMessage("message-1", isPrompt = true, alias = "otid-1", text = "plan the trip"),
        BranchMessage("message-2", isPrompt = false, text = "Here is a plan."),
        BranchMessage("message-3", isPrompt = true, alias = "otid-3", text = "add a museum"),
        BranchMessage("message-4:reasoning:0", isPrompt = false),
        BranchMessage("message-4:tool:call-1:request", isPrompt = false),
        BranchMessage("message-5", isPrompt = false),
        BranchMessage("message-6", isPrompt = false, text = "Added the Louvre."),
    )

    @Test
    fun aStreamedReplyIsForkedThroughTheIdTheServerStoredItUnder() = runTest {
        val backend = Backend(stored)
        val streamed = reply("cm-stream-message-2", "Here is a plan.")

        ConversationBranching(backend.asBackend()).forkFrom(source(listOf(prompt("message-1", "plan the trip"), streamed)), streamed)

        assertEquals(listOf("message-2"), backend.forkedThrough)
    }

    @Test
    fun aRowKnownOnlyByAClientIdIsFoundByItsText() = runTest {
        val backend = Backend(stored)
        val unechoed = reply("lm-7f3a", "Added the Louvre.").copy(clientMessageId = "lm-7f3a")

        ConversationBranching(backend.asBackend()).forkFrom(source(listOf(unechoed)), unechoed)

        assertEquals(listOf("message-6"), backend.forkedThrough)
    }

    @Test
    fun aPromptWithToolCallsKeepsItsWholeExchange() = runTest {
        val backend = Backend(stored)

        ConversationBranching(backend.asBackend()).forkFrom(source(emptyList()), prompt("message-3", "add a museum"))

        assertEquals(listOf("message-6"), backend.forkedThrough, "the fork ends with the reply after the tool call, not on reasoning")
    }

    @Test
    fun aTimelineReasoningRowIsNeverSentAsAForkPoint() = runTest {
        val backend = Backend(history = null)
        val page = listOf(
            prompt("message-3", "add a museum"),
            reply("message-4:REASONING", "thinking").copy(isReasoning = true),
            reply("cm-stream-message-6", "Added the Louvre."),
        )

        ConversationBranching(backend.asBackend()).forkFrom(source(page), page[0])

        assertEquals(listOf("message-3"), backend.forkedThrough, "without the server's list only server ids may be sent")
    }

    @Test
    fun aReplyStillStreamingDuringATurnCannotBeForkedYet() = runTest {
        val backend = Backend(stored)
        val streaming = reply("cm-stream-msg-live", "Partial ans")

        val failure = assertFailsWith<BranchUnavailableException> {
            ConversationBranching(backend.asBackend()).forkFrom(source(listOf(streaming)), streaming)
        }

        assertEquals(ConversationBranching.NOT_SAVED_YET, failure.message)
        assertEquals(emptyList(), backend.forkedThrough)
    }

    @Test
    fun aPromptCanBeRerunWhileItsTurnIsStillRunning() = runTest {
        val backend = Backend(stored.take(3))
        val running = listOf(prompt("message-3", "add a museum"), reply("cm-stream-msg-live", "Adding").copy(isPending = true))

        val rerun = ConversationBranching(backend.asBackend()).rerun(source(running), running[0])

        assertEquals(listOf("message-2"), backend.forkedThrough)
        assertEquals("add a museum", rerun.prompt.text)
    }

    @Test
    fun editingAPromptOnAShortLiveTailForksFromTheServerNotAnEmptyConversation() = runTest {
        val backend = Backend(stored)
        // Android's paged timeline: the page holds only the newest rows and says nothing is older.
        val tail = listOf(prompt("message-3", "add a museum"))

        val edit = ConversationBranching(backend.asBackend()).editAndResend(source(tail, historyComplete = true), tail[0])

        assertEquals(listOf("message-2"), backend.forkedThrough)
        assertEquals(emptyList(), backend.created, "an edit mid-conversation must never start an empty conversation")
        assertEquals("add a museum", edit.draft)
    }

    @Test
    fun aRerunCarriesThePromptsImages() = runTest {
        val backend = Backend(stored)
        val withImage = prompt("message-3", "add a museum").copy(attachments = listOf(UiImageAttachment("aGk=", "image/png")))

        val rerun = ConversationBranching(backend.asBackend()).rerun(source(emptyList()), withImage)

        assertEquals(listOf("aGk="), rerun.prompt.images.map { it.base64 })
    }

    @Test
    fun aPromptWhoseImagesWereNotKeptCannotBeRerun() {
        val pointerOnly = prompt("message-3", "add a museum").copy(attachments = listOf(UiImageAttachment("", "image/png", storedByteSize = 9)))
        assertFalse(ConversationBranchPlanner.canRerun(pointerOnly))
        assertTrue(ConversationBranchPlanner.canRerun(prompt("message-3", "add a museum")))
    }

    @Test
    fun aNamedForkIsRenamedAfterItIsMade() = runTest {
        val backend = Backend(stored)

        val fork = ConversationBranching(backend.asBackend()).forkFrom(source(emptyList()), prompt("message-1", "plan the trip"), name = "  Trip, plan B ")

        assertEquals("Trip, plan B", fork.summary)
        assertEquals(listOf("fork-1" to "Trip, plan B"), backend.renames)
    }

    @Test
    fun serverMessagesReadBackCarryTheirTextAndOtid() {
        val user = BranchMessage.of(UserMessage(id = "message-1", contentRaw = JsonPrimitive("hi"), date = "d", otid = "otid-1"))
        val assistant = BranchMessage.of(AssistantMessage(id = "message-2", contentRaw = JsonPrimitive("hello"), date = "d"))

        assertEquals(BranchMessage("message-1", isPrompt = true, alias = "otid-1", text = "hi"), user)
        assertEquals(BranchMessage("message-2", isPrompt = false, text = "hello"), assistant)
    }

    private fun source(timeline: List<com.letta.mobile.data.model.UiMessage>, historyComplete: Boolean = false) =
        BranchSource("conv-1", "agent-1", timeline, historyComplete)

    private class Backend(private val history: List<BranchMessage>?) {
        val forkedThrough = mutableListOf<String>()
        val created = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()

        fun asBackend() = BranchBackend(
            fork = { request -> forkedThrough += request.throughMessageId.orEmpty(); conversation("fork-${forkedThrough.size}") },
            createEmpty = { agentId -> created += agentId; conversation("empty") },
            history = history?.let { messages -> { _ -> BranchHistory(messages, complete = true) } },
            rename = { fork, name -> renames += fork.id.value to name; fork.copy(summary = name) },
        )
    }
}
