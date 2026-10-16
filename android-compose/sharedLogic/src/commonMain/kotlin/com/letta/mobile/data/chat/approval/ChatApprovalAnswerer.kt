package com.letta.mobile.data.chat.approval

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatApprovalStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One answer to deliver: the card's [answer], the conversation it belongs to, and how it goes out. */
class ChatApprovalSubmission(
    val answer: ChatApprovalAnswer,
    /** The conversation whose timeline decides when the answer has landed. */
    val conversation: ConversationId,
    /**
     * Sends the answer over the host's transport (or its offline outbox). Throwing counts as
     * [ChatApprovalResult.Failed]; cancellation releases the card without an error.
     */
    val deliver: suspend () -> ChatApprovalResult,
)

/** What a host shows at each step of an answer; the [ChatApprovalAnswerer] owns the in-flight state. */
interface ChatApprovalAnswerHost {
    /** The answer is in flight: its card disables. */
    fun onInFlight(answer: ChatApprovalAnswer) = Unit

    /** The answer went out (or waits in the outbox, [ChatApprovalResult.Queued]); it stays in flight. */
    fun onAccepted(answer: ChatApprovalAnswer, result: ChatApprovalResult) = Unit

    /** The answer did not go out: its card is actionable again and [message] says why. */
    fun onRejected(answer: ChatApprovalAnswer, message: String) = Unit

    /** The delivery was cancelled: the card is actionable again, with nothing to report. */
    fun onCancelled(answer: ChatApprovalAnswer) = Unit
}

/**
 * letta-mobile-by4vm.78: the approval-answer lifecycle, one implementation for Android's chat screen
 * and desktop's controller (each used to carry its own submit / release / reconcile pipeline around
 * the shared [ApprovalSubmissionTracker]); letta-mobile-by4vm.93: also both clients' one
 * [ChatApprovalStateSource], with the host's outbox and parked requests mirrored by [bind].
 *
 * An answer is in flight from [submit] until the conversation's timeline stops showing its request
 * as pending ([reconcile]); a delivery that fails or is cancelled releases it at once. A request with
 * parallel calls is answered one call at a time: once the answered call's gate resolved and a sibling
 * is parked in its place ([releaseAnsweredCalls]), the card is for that sibling and is actionable again.
 *
 * Call it from the host's own scope (the main thread on both clients); its bookkeeping is not
 * synchronised, like the tracker's.
 */
class ChatApprovalAnswerer(
    private val scope: CoroutineScope,
    private val host: ChatApprovalAnswerHost = object : ChatApprovalAnswerHost {},
) : ChatApprovalStateSource {
    private val tracker = ApprovalSubmissionTracker()

    /**
     * Requests answered one parallel call at a time: request id to (the call answered, its sibling
     * calls). The tracker holds such a request in flight until the whole request is decided.
     */
    private val answeredCalls = LinkedHashMap<String, AnsweredCall>()

    /** Request ids with an answer in flight, in submission order. */
    override val submittingApprovals: StateFlow<Set<String>> = tracker.submitting

    private val queued = MutableStateFlow<Set<String>>(emptySet())
    override val queuedApprovals: StateFlow<Set<String>> = queued.asStateFlow()

    private val parked = MutableStateFlow<Map<String, PendingApprovalDetails>>(emptyMap())
    override val pendingApprovalDetails: StateFlow<Map<String, PendingApprovalDetails>> = parked.asStateFlow()
    private var binding: Job? = null

    /** Marks the answer in flight and delivers it on the host's scope. */
    fun submit(submission: ChatApprovalSubmission) {
        val answer = submission.answer
        tracker.begin(answer.requestId, submission.conversation.value)
        answer.suggestionBinding?.let { binding ->
            answeredCalls[answer.requestId] = AnsweredCall(binding.toolCallId, (answer.toolCallIds - binding.toolCallId).toSet())
        }
        host.onInFlight(answer)
        scope.launch { deliver(submission) }
    }

    private suspend fun deliver(submission: ChatApprovalSubmission) {
        val answer = submission.answer
        val result = try {
            submission.deliver()
        } catch (cancelled: CancellationException) {
            clear(answer.requestId)
            host.onCancelled(answer)
            throw cancelled
        } catch (failure: Exception) {
            ChatApprovalResult.Failed(failure.message ?: failure::class.simpleName ?: DELIVERY_FAILED)
        }
        val error = result.errorMessage
        if (error == null) {
            host.onAccepted(answer, result)
        } else {
            clear(answer.requestId)
            host.onRejected(answer, error)
        }
    }

    /** The answer to [request] is no longer in flight: its card is actionable again. */
    fun clear(request: String) {
        answeredCalls.remove(request)
        tracker.clear(request)
    }

    /**
     * The timeline for [conversation] now shows [pendingRequests] as pending; every in-flight answer
     * of that conversation that is no longer pending has landed. Returns the request ids it cleared.
     */
    fun reconcile(conversation: ConversationId, pendingRequests: Set<String>): Set<String> {
        if (tracker.submitting.value.isEmpty()) return emptySet()
        val decided = tracker.reconcile(conversation.value, pendingRequests)
        decided.forEach(answeredCalls::remove)
        return decided
    }

    /** Mirrors the host's outbox and parked requests (null: it has none); parked snapshots also [releaseAnsweredCalls]. */
    fun bind(queuedRequests: StateFlow<Set<String>>?, parkedRequests: StateFlow<Map<String, PendingApprovalDetails>>?) {
        binding?.cancel()
        queued.value = emptySet()
        parked.value = emptyMap()
        if (queuedRequests == null && parkedRequests == null) return
        binding = scope.launch {
            queuedRequests?.let { source -> launch { source.collect { queued.value = it } } }
            parkedRequests?.collect {
                parked.value = it
                releaseAnsweredCalls(it)
            }
        }
    }

    /** The host's parked control requests are now [parkedCalls] (by tool call id). */
    fun releaseAnsweredCalls(parkedCalls: Map<String, PendingApprovalDetails>) {
        val released = answeredCalls.filterValues { it.call !in parkedCalls && it.siblings.any { sibling -> sibling in parkedCalls } }.keys
        released.toList().forEach(::clear)
    }

    private class AnsweredCall(val call: String, val siblings: Set<String>)

    private companion object {
        const val DELIVERY_FAILED = "Could not submit answer"
    }
}
