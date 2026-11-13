package com.letta.mobile.data.chat.sentruns

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A send pipeline's identified send: one text sent through the pipeline's own locked send, and the
 * otid it was enqueued under.
 *
 * The pipeline takes each new otid from [nextOtid], under [lock], only once a send is about to be
 * enqueued; so the otid taken during this send's own hold of [lock] is the one it enqueued, and
 * none was taken when the send returned before enqueuing. [target] is read, in the caller's turn,
 * before the hold, and [sendLocked] runs in [scope] under it.
 */
internal class IdentifiedPipelineSend(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val generateOtid: () -> String,
    private val target: () -> String?,
    private val sendLocked: suspend (text: String, target: String?) -> Unit,
) {
    /** The otid the pipeline took last; read and written only under [lock]. */
    private var taken: String? = null

    /** A new otid for a send the pipeline enqueues next; call under [lock]. */
    fun nextOtid(): String = generateOtid().also { taken = it }

    /** This send as an [IdentifiedChatSend] over the pipeline's published [sentRuns]. */
    fun binding(sentRuns: StateFlow<SentMessageRuns>): IdentifiedChatSend = IdentifiedSendBinding(sentRuns, ::send)

    private suspend fun send(text: String): SentMessageId? {
        val conversation = target()
        val enqueued = scope.async {
            lock.withLock {
                taken = null
                sendLocked(text, conversation)
                taken
            }
        }
        return enqueued.await()?.let(::SentMessageId)
    }
}
