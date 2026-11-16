package com.letta.mobile.data.lanes.reply

import kotlin.random.Random

/** Shared reply policy; the host supplies atomic durable transactions and its clock. */
class ReplyStore(
    private val transaction: suspend ((ReplySnapshot) -> ReplySnapshot) -> ReplySnapshot,
    private val clock: () -> Long,
    private val endpointFor: (String) -> String?,
    private val processEpoch: String = "test-process",
    private val canonicalAgent: (String) -> String = { it },
) {
    /** Records the verified caller before any request can leave the host. */
    suspend fun request(origin: ReplyOrigin, replier: String): ReplyTo {
        val endpoint = requireNotNull(endpointFor(replier)) { "Reply routing requires a known local replier identity" }
        val now = clock()
        val address = ReplyTo(uuidV7(now), now + REPLY_TTL_MS)
        transaction { state -> prune(state, now).copy(pending = prune(state, now).pending + PendingReply(
            address.correlationId, canonicalAgent(origin.agent), origin.conversation,
            canonicalAgent(replier), endpoint, now, address.expiresAt,
        )) }
        return address
    }

    /** A successful return means the held body and its duplicate key are journaled. */
    suspend fun receive(id: String, recipient: String, sender: String, endpoint: String,
                        messageKey: String, body: String, more: Boolean = false): ReplyMatch {
        var match = ReplyMatch.INBOX
        val now = clock()
        transaction { original ->
            val row = original.pending.firstOrNull { it.correlationId == id && it.originAgent == canonicalAgent(recipient) }
            val state = prune(original, now)
            val receipt = state.received.firstOrNull { it.messageKey == messageKey }
            when {
                receipt != null -> {
                    match = if (receipt.correlationId == id && receipt.recipient == canonicalAgent(recipient) &&
                        receipt.sender == canonicalAgent(sender) && receipt.endpoint == endpoint) ReplyMatch.HELD else ReplyMatch.MISMATCH
                    state
                }
                row == null || row.expiresAt <= now -> if (state.deadLetters.any { it.messageKey == messageKey }) state
                    else state.copy(deadLetters = state.deadLetters + ReplyDeadLetter(
                        id, messageKey, if (row == null && id !in state.expired) "unknown" else "expired", now,
                    ))
                row.expectedReplier != canonicalAgent(sender) || row.expectedEndpoint != endpoint -> {
                    match = ReplyMatch.MISMATCH
                    state
                }
                else -> {
                    match = ReplyMatch.HELD
                    state.copy(pending = if (more) state.pending else state.pending.filterNot { it.correlationId == id },
                        held = state.held + HeldReply(row, canonicalAgent(sender), messageKey, body),
                        received = state.received + ReplyReceipt(messageKey, id, canonicalAgent(recipient),
                            canonicalAgent(sender), endpoint, now + RECEIPT_TTL_MS))
                }
            }
        }
        return match
    }

    /** Claims live replies for one input; other simultaneous inputs cannot attach them. */
    suspend fun held(origin: ReplyOrigin, inputToken: String): List<HeldReply> =
        transaction { original ->
            val state = prune(original, clock())
            state.copy(held = state.held.map { reply ->
                val scopeMatches = reply.request.originAgent == canonicalAgent(origin.agent) &&
                    reply.request.originConversation == origin.conversation
                val available = reply.inputToken == null || reply.processEpoch != processEpoch
                if (scopeMatches && available) reply.copy(inputToken = inputToken, processEpoch = processEpoch) else reply
            })
        }.held.filter { it.inputToken == inputToken && it.processEpoch == processEpoch &&
            it.request.originAgent == canonicalAgent(origin.agent) && it.request.originConversation == origin.conversation }

    /** A refused input releases its claim without losing the held reply. */
    suspend fun release(replies: List<HeldReply>) {
        transaction { state -> state.copy(held = state.held.map { reply ->
            if (reply in replies) reply.copy(inputToken = null, processEpoch = null) else reply
        }) }
    }

    /** Called only after upstream accepts the input carrying these replies. */
    suspend fun consume(replies: List<HeldReply>) {
        val keys = replies.map { it.messageKey }.toSet()
        transaction { it.copy(held = it.held.filterNot { reply -> reply.messageKey in keys }) }
    }

    private fun prune(state: ReplySnapshot, now: Long): ReplySnapshot {
        val expired = state.held.filter { it.request.expiresAt <= now }
        return state.copy(received = state.received.filter { it.retainedUntil > now },
            expired = state.expired + state.pending.filter { it.expiresAt <= now }.map { it.correlationId },
            pending = state.pending.filter { it.expiresAt > now },
            held = state.held.filter { it.request.expiresAt > now },
            deadLetters = state.deadLetters + expired.map {
                ReplyDeadLetter(it.request.correlationId, it.messageKey, "expired", now)
            })
    }

    companion object {
        /** Seven days, matching the frozen request expiry. */
        private const val RECEIPT_TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

        const val REPLY_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Provenance is outside the quoted body; JSON escaping prevents delimiter imitation. */
        fun prepend(replies: List<HeldReply>, text: String): String = replies.joinToString("") {
            "<untrusted_agent_reply>\nreply_from: ${it.sender}\ncorrelation_id: ${it.request.correlationId}\n" +
                "body_json: ${kotlinx.serialization.json.JsonPrimitive(it.body)}\n</untrusted_agent_reply>\n\n"
        } + text
    }
}

private fun uuidV7(now: Long): String {
    val bytes = Random.nextBytes(16)
    repeat(6) { bytes[it] = (now ushr ((5 - it) * 8)).toByte() }
    bytes[6] = ((bytes[6].toInt() and 15) or 112).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
