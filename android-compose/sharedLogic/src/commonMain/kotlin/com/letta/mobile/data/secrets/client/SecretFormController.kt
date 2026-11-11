package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretRequestDetails
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The state holder behind one secure form: it owns the only copy of what the person types (a
 * [SecretBuffer]), runs [SecretFormMachine], and talks to the [SecretRequestClient].
 *
 * The value is zeroed at every exit:
 * - on submit, the buffer is turned into a [SecretInput] and zeroed before the call starts, and the
 *   [SecretInput] is wiped when the call returns, throws or is cancelled;
 * - on decline, on expiry (timer, or the request leaving the host's pending list), on retry and on
 *   [dispose], the buffer is zeroed.
 *
 * The target comes from the request and has no setter: nothing the form does can change where the
 * value goes. All calls are made from one thread (the UI thread); [scope] is the form's own, so
 * cancelling it (the form leaving composition) cancels a call in flight, which still wipes the value.
 */
class SecretFormController(
    private val request: SecretRequestRecord,
    private val client: SecretRequestClient,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val buffer = SecretBuffer()
    private val mutableState = MutableStateFlow<SecretFormState>(SecretFormState.Idle)
    private var expiryJob: Job? = null

    /** The form's state; it never holds the value. */
    val state: StateFlow<SecretFormState> = mutableState.asStateFlow()

    /** The request this form answers. */
    val requestId: SecretRequestId get() = request.requestId

    /** Where the value will be written, fixed by the host when the request was made. */
    val target: SecretRequestTarget get() = request.details.target

    /** Who asked, for what and why (the purpose is agent-authored plain text). */
    val details: SecretRequestDetails get() = request.details

    /**
     * The protections the platform reported for the surface showing this form (see
     * [surfaceShown]); a later stage reports them honestly with the submission.
     */
    var surfaceCapabilities: SecureSurfaceCapabilities = SecureSurfaceCapabilities.SharedOnly
        private set

    /** Records the protections the platform put around the form while it is shown. */
    fun surfaceShown(capabilities: SecureSurfaceCapabilities) {
        surfaceCapabilities = capabilities
    }

    /** Opens the form (Idle to Editing) and arms the expiry timer; an overdue request expires instead. */
    fun open() {
        if (isOverdue()) {
            expire()
            return
        }
        if (transition(SecretFormEvent.Open)) armExpiry()
    }

    /**
     * Applies one edit of the field to the buffer: [range] replaced by [text]. Answers false, and
     * changes nothing, when the form is not editing or the edit is out of bounds or too long.
     */
    fun edit(range: IntRange, text: CharSequence): Boolean {
        if (mutableState.value !is SecretFormState.Editing) return false
        val accepted = buffer.replace(range, text)
        transition(SecretFormEvent.Input(if (buffer.isEmpty) SecretInputPresence.EMPTY else SecretInputPresence.ENTERED))
        return accepted
    }

    /** Lends [block] the typed characters for the explicit reveal toggle; see [SecretBuffer.revealChars]. */
    fun <R> reveal(block: (CharSequence) -> R): R = buffer.revealChars(block)

    /**
     * Submits the value once: only from Editing with something entered, and only before the
     * request's expiry. Answers whether a submission started; a second press while submitting,
     * an empty field or an overdue request starts nothing.
     */
    fun submit(): Boolean {
        if (isOverdue()) {
            expire()
            return false
        }
        if (buffer.isEmpty) return false
        if (!transition(SecretFormEvent.Submit)) return false
        val value = buffer.takeSecretInput()
        scope.launch {
            val outcome = try {
                send(value)
            } finally {
                value.wipe()
            }
            transition(SecretFormEvent.Answered(outcome))
        }
        return true
    }

    /** Declines the request: the buffer is zeroed and the host told. Not allowed while submitting. */
    fun decline() {
        if (!transition(SecretFormEvent.Decline)) return
        buffer.wipe()
        expiryJob?.cancel()
        scope.launch { quietly { client.decline(request.requestId) } }
    }

    /** Expires the form (the timer, or the request gone from the host's list); the buffer is zeroed. */
    fun expire() {
        if (!transition(SecretFormEvent.Expire)) return
        buffer.wipe()
        expiryJob?.cancel()
    }

    /** After a retryable failure, starts over with an empty field. */
    fun retry() {
        if (transition(SecretFormEvent.Retry)) buffer.wipe()
    }

    /**
     * Zeroes the buffer, marks the field empty and stops the timer: the form is gone. A call in
     * flight is left to [scope].
     */
    fun dispose() {
        buffer.wipe()
        transition(SecretFormEvent.Input(SecretInputPresence.EMPTY))
        expiryJob?.cancel()
    }

    /** Never the request text or the value. */
    override fun toString(): String = "SecretFormController(${request.requestId.logLabel}, ${mutableState.value})"

    private suspend fun send(value: SecretInput): SecretClientOutcome =
        quietly { client.submit(request.requestId, value) } ?: SecretClientOutcome.Unreachable

    private fun armExpiry() {
        val remaining = request.timing.expiresAtMs - clock.now().toEpochMilliseconds()
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(remaining.milliseconds)
            expire()
        }
    }

    private fun isOverdue(): Boolean = clock.now().toEpochMilliseconds() >= request.timing.expiresAtMs

    private fun transition(event: SecretFormEvent): Boolean {
        val before = mutableState.value
        val after = SecretFormMachine.next(before, event)
        return after != before && mutableState.compareAndSet(before, after)
    }
}

/**
 * [block]'s answer, or null when it threw: a failed call must not surface its exception, whose
 * message or cause could carry request text. Cancellation still propagates.
 */
private suspend fun <T : Any> quietly(block: suspend () -> T): T? {
    val result = runCatching { block() }
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return result.getOrNull()
}
