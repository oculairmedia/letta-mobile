package com.letta.mobile.data.chat.approval

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.binding
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-by4vm.78: the approval-answer lifecycle Android and desktop share. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatApprovalAnswererTest {
    private val conversation = ConversationId("conv-1")
    private val host = RecordingHost()

    @Test
    fun anAcceptedAnswerStaysInFlightUntilTheTimelineDecidesIt() = runTest {
        val answerer = answerer()
        answerer.submit(submission(answer("req-1")) { ChatApprovalResult.Submitted })
        runCurrent()

        assertEquals(listOf("inFlight req-1", "accepted req-1 Submitted"), host.steps)
        assertEquals(setOf("req-1"), answerer.submittingApprovals.value)
        assertEquals(emptySet(), answerer.reconcile(conversation, pendingRequests = setOf("req-1")))
        assertEquals(emptySet(), answerer.reconcile(ConversationId("conv-2"), pendingRequests = emptySet()), "another conversation decides nothing")

        assertEquals(setOf("req-1"), answerer.reconcile(conversation, pendingRequests = emptySet()))
        assertTrue(answerer.submittingApprovals.value.isEmpty())
    }

    @Test
    fun aQueuedAnswerIsAcceptedAndStaysInFlight() = runTest {
        val answerer = answerer()
        answerer.submit(submission(answer("req-1")) { ChatApprovalResult.Queued })
        runCurrent()

        assertEquals("accepted req-1 Queued", host.steps.last())
        assertEquals(setOf("req-1"), answerer.submittingApprovals.value)
    }

    @Test
    fun aRejectedOrThrowingDeliveryReleasesTheCardWithItsMessage() = runTest {
        val answerer = answerer()
        answerer.submit(submission(answer("req-1")) { ChatApprovalResult.MissingActiveConversation })
        answerer.submit(submission(answer("req-2")) { error("socket closed") })
        runCurrent()

        assertTrue(answerer.submittingApprovals.value.isEmpty())
        assertTrue("rejected req-1 No active conversation available for approval" in host.steps)
        assertTrue("rejected req-2 socket closed" in host.steps)
    }

    @Test
    fun aCancelledDeliveryReleasesTheCardWithoutAnError() = runTest {
        val delivery = Job()
        val answerer = ChatApprovalAnswerer(CoroutineScope(backgroundScope.coroutineContext + delivery), host)
        val never = CompletableDeferred<ChatApprovalResult>()
        answerer.submit(submission(answer("req-1")) { never.await() })
        runCurrent()
        assertEquals(setOf("req-1"), answerer.submittingApprovals.value)

        delivery.cancel()
        runCurrent()

        assertTrue(answerer.submittingApprovals.value.isEmpty())
        assertEquals("cancelled req-1", host.steps.last())
    }

    @Test
    fun theNextParallelCallIsActionableOnceTheAnsweredOneResolves() = runTest {
        val answerer = answerer()
        answerer.submit(submission(answer("req-1", calls = listOf("A", "B"), answering = "A")) { ChatApprovalResult.Submitted })
        runCurrent()

        answerer.releaseAnsweredCalls(mapOf("A" to details("A"), "B" to details("B")))
        assertEquals(setOf("req-1"), answerer.submittingApprovals.value, "A is still parked")

        answerer.releaseAnsweredCalls(mapOf("B" to details("B")))
        assertTrue(answerer.submittingApprovals.value.isEmpty(), "the card redrew for B")
    }

    @Test
    fun aSingleCallWithNoSiblingWaitsForTheTimeline() = runTest {
        val answerer = answerer()
        answerer.submit(submission(answer("req-1", calls = listOf("A"), answering = "A")) { ChatApprovalResult.Submitted })
        runCurrent()

        answerer.releaseAnsweredCalls(emptyMap())
        assertEquals(setOf("req-1"), answerer.submittingApprovals.value)
    }

    private fun TestScope.answerer() = ChatApprovalAnswerer(backgroundScope, host)

    private fun submission(answer: ChatApprovalAnswer, deliver: suspend () -> ChatApprovalResult) =
        ChatApprovalSubmission(answer, conversation, deliver)

    private fun answer(request: String, calls: List<String> = listOf("A"), answering: String? = null) = ChatApprovalAnswer(
        requestId = request,
        toolCallIds = calls,
        approve = true,
        reason = null,
        suggestionBinding = answering?.let { details(it).binding },
    )

    private fun details(call: String) = PendingApprovalDetails(approvalId = "perm-$call", toolCallId = call, toolName = "Bash")

    private class RecordingHost : ChatApprovalAnswerHost {
        val steps = mutableListOf<String>()

        override fun onInFlight(answer: ChatApprovalAnswer) {
            steps += "inFlight ${answer.requestId}"
        }

        override fun onAccepted(answer: ChatApprovalAnswer, result: ChatApprovalResult) {
            steps += "accepted ${answer.requestId} $result"
        }

        override fun onRejected(answer: ChatApprovalAnswer, message: String) {
            steps += "rejected ${answer.requestId} $message"
        }

        override fun onCancelled(answer: ChatApprovalAnswer) {
            steps += "cancelled ${answer.requestId}"
        }
    }
}
