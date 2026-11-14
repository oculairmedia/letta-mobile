package com.letta.mobile.data.chat.sentruns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A send pipeline's identified send: one text sent through the pipeline's own locked send, and the
 * otid it was enqueued under.
 *
 * The pipeline takes each new otid from [nextOtid], under [lock], only once a send is about to be
 * enqueued; so the otid taken during this send's own hold of [lock] is the one it enqueued, and
 * none was taken when the send returned before enqueuing. [target] is read, in the caller's turn,
 * before the hold, and [sendLocked] runs in [scope] under it.
 *
 * Once started, a send is not withdrawn by its caller's cancellation: the enqueue runs to its end
 * and the caller still receives the otid it was enqueued under, so a message that went out always
 * has an identity its sender can follow or stop. Likewise when the pipeline's work after accepting
 * the send fails (a timeline write, say): the failure goes to [failedAfterAccept] and the caller
 * still receives the otid. A failure before the send was accepted reaches the caller, with no otid.
 */
internal class IdentifiedPipelineSend(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val generateOtid: () -> String,
    private val failedAfterAccept: (Throwable) -> Unit,
    private val target: () -> String?,
    private val sendLocked: suspend (text: String, target: String?) -> Unit,
) {
    /** The otid the pipeline took last; read and written only under [lock]. */
    private var taken: String? = null

    /** Whether the send behind [taken] was refused before it was accepted or queued; under [lock]. */
    private var refused = false

    /** A new otid for a send the pipeline enqueues next; call under [lock]. */
    fun nextOtid(): String = generateOtid().also { taken = it }

    /**
     * The send enqueued under [otid] was refused before it was accepted or queued (for example the
     * pending store rejected it): it has no identity to report. Call under [lock].
     */
    fun refuse(otid: String) {
        if (otid == taken) refused = true
    }

    /** This send as an [IdentifiedChatSend] over the pipeline's sent-run [ledger]. */
    fun binding(ledger: SentRunLedger): IdentifiedChatSend =
        IdentifiedSendBinding(ledger.state) { text -> send(OutgoingText(text, target()), ledger) }

    private suspend fun send(outgoing: OutgoingText, ledger: SentRunLedger): SentMessageId? {
        // The child returns its outcome as a value: a failure must reach this caller, never cancel [scope].
        val enqueued = scope.async { runCatching { lock.withLock { enqueue(outgoing, ledger) } } }
        return withContext(NonCancellable) { enqueued.await() }.getOrThrow()
    }

    /** Under [lock]: sends [outgoing] and returns the otid it was enqueued under, if any. */
    private suspend fun enqueue(outgoing: OutgoingText, ledger: SentRunLedger): SentMessageId? {
        taken = null
        refused = false
        val failure = runCatching { sendLocked(outgoing.text, outgoing.conversation) }.exceptionOrNull()
            ?: return taken?.takeUnless { refused }?.let(::SentMessageId)
        if (failure is CancellationException) throw failure
        return acceptedDespite(failure, ledger)
    }

    /**
     * The send threw [failure]: the otid taken, when [ledger] shows the pipeline had already
     * accepted that send (the failure goes to [failedAfterAccept]); otherwise [failure] is rethrown.
     */
    private fun acceptedDespite(failure: Throwable, ledger: SentRunLedger): SentMessageId {
        val sent = taken?.let(::SentMessageId)?.takeIf(ledger::wasSent) ?: throw failure
        failedAfterAccept(failure)
        return sent
    }
}

/** A text to send and the conversation it goes to, as read in the caller's turn. */
private class OutgoingText(val text: String, val conversation: String?)
