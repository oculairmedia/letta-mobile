package com.letta.mobile.data.messaging

/**
 * letta-mobile-slqfp: structural (non-heuristic) encoding of inbound
 * inter-agent provenance onto the existing `clientMessageId` wire field.
 *
 * `clientMessageId` already survives Local -> Confirmed reconciliation end to
 * end as an opaque string (see `TimelineExternalTransportAppender`,
 * `TimelineSyncReconcile`, `TimelineEventToUiMessage`), so it is the one
 * identifier the receiving client is guaranteed to see for a message the App
 * Server persisted on its behalf. This is a FIXED, VERSIONED, delimiter-based
 * schema — the projection layer only ever recognizes this exact prefixed
 * shape; it never inspects message body/content text. If the id doesn't
 * match, [decode] returns null and the message renders as ordinary chat —
 * per the "no content heuristics" acceptance criterion on letta-mobile-slqfp.
 *
 * Format: `a2a:v1:<msgId>:<fromAgentId>:<toAgentId>`
 *
 * Letta agentIds are UUIDs or `agent-<uuid>` — colon-free — so `:` is a safe
 * delimiter. [encode] defensively falls back to the plain [msgId] (i.e. no
 * provenance survives) rather than emit an ambiguous id if any component
 * contains the delimiter.
 */
object AgentMessageClientId {
    private const val PREFIX = "a2a:v1:"
    private const val PART_COUNT = 3

    fun encode(msgId: String, fromAgentId: String, toAgentId: String): String {
        val parts = Parts(msgId, fromAgentId, toAgentId)
        return parts.encodedOrNull() ?: msgId
    }

    data class Decoded(val msgId: String, val fromAgentId: String, val toAgentId: String)

    fun decode(clientMessageId: String?): Decoded? = clientMessageId
        ?.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)
        ?.split(':')
        ?.toDecoded()

    fun dedupIdentity(clientMessageId: String): String = decode(clientMessageId)?.msgId ?: clientMessageId

    private data class Parts(
        val msgId: String,
        val fromAgentId: String,
        val toAgentId: String,
    ) {
        private val values = listOf(msgId, fromAgentId, toAgentId)

        fun encodedOrNull(): String? = values
            .takeUnless { parts -> parts.any { it.contains(':') } }
            ?.joinToString(separator = ":", prefix = PREFIX)
    }

    private fun List<String>.toDecoded(): Decoded? = takeIf { it.size == PART_COUNT }
        ?.takeUnless { parts -> parts.any(String::isBlank) }
        ?.let { (msgId, fromAgentId, toAgentId) -> Decoded(msgId, fromAgentId, toAgentId) }
}
