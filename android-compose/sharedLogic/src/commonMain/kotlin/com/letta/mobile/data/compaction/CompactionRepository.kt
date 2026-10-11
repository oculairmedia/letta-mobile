package com.letta.mobile.data.compaction

import com.letta.mobile.data.chat.runtime.SharedChatSessionResolver
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/** letta-mobile-3kble: compact one conversation, optionally in a given [mode]. */
data class CompactionRequest(
    val agentId: AgentId,
    /** Null is the agent's default conversation. */
    val conversationId: ConversationId?,
    val mode: CompactionMode? = null,
) {
    /**
     * The id letta-code uses: the bare `default` for the agent's default conversation, whether the
     * app named it that way or by its addressable `conv-default-<agentId>` alias.
     */
    val wireConversationId: String
        get() = wireConversationIdOf(agentId, conversationId)
}

/**
 * letta-mobile-joigh: the conversation id letta-code uses — the bare `default` for the agent's
 * default conversation (null, blank, or its addressable `conv-default-<agentId>` alias), else the
 * id itself. Slash commands scope by it: on `default` they act on the agent.
 */
fun wireConversationIdOf(agentId: AgentId, conversationId: ConversationId?): String {
    val id = conversationId?.value?.takeIf { it.isNotBlank() } ?: return ConversationCompactRpc.DEFAULT_CONVERSATION
    return if (id == SharedChatSessionResolver.DEFAULT_SHIM_CONVERSATION_PREFIX + agentId.value) {
        ConversationCompactRpc.DEFAULT_CONVERSATION
    } else {
        id
    }
}

/** What a compaction attempt came to. */
sealed interface CompactionOutcome {
    /** The transcript shrank. */
    data class Compacted(val result: ConversationCompactResult) : CompactionOutcome

    /** letta-code ran it but nothing changed ("Already compact"). */
    data class AlreadyCompact(val result: ConversationCompactResult) : CompactionOutcome

    /** The host is still summarizing; the result arrives with the next turn. */
    data object Pending : CompactionOutcome

    /** This backend cannot compact; the button hides. */
    data object Unsupported : CompactionOutcome

    /** Another compaction of the same conversation is already running. */
    data object Busy : CompactionOutcome

    data class Failed(val message: String) : CompactionOutcome
}

/**
 * letta-mobile-3kble: manual compaction, whichever way the session reaches letta-code.
 *
 *  - [AdminRpcCompactionRepository]: the Iroh host's `conversation.compact` (S3);
 *  - [AppServerCompactionRepository]: a direct App Server client (desktop-bundled, embedded
 *    Android, direct WS) — `execute_command compact` when the server lists `compact` in
 *    `supported_commands` (the only path on embedded 0.26.1, which answers with
 *    `slash_command_end` and no response frame), else `conversation_compact` (0.29+), else
 *    [CompactionOutcome.Unsupported].
 */
interface CompactionRepository {
    suspend fun compact(request: CompactionRequest): CompactionOutcome
}

/**
 * letta-mobile-3io8k: tries this repository, then [fallback] when this one cannot compact at all
 * (e.g. the Iroh relay first, then a desktop's bundled App Server). Any other outcome stands.
 */
fun CompactionRepository.orElse(fallback: CompactionRepository): CompactionRepository = object : CompactionRepository {
    override suspend fun compact(request: CompactionRequest): CompactionOutcome {
        val first = this@orElse.compact(request)
        return if (first == CompactionOutcome.Unsupported) fallback.compact(request) else first
    }
}

/** Maps a successful result onto [CompactionOutcome.Compacted] or [CompactionOutcome.AlreadyCompact]. */
internal fun ConversationCompactResult.toOutcome(): CompactionOutcome =
    if (noChange) CompactionOutcome.AlreadyCompact(this) else CompactionOutcome.Compacted(this)
