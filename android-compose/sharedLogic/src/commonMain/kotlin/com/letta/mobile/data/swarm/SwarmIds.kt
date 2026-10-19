@file:OptIn(ExperimentalUuidApi::class)

package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The idempotency key of one swarm message. Minted once per send, written to the
 * outbox before anything is dialled and sent as the input's `client_message_id`, so a retry or a
 * resume is recognisably the same message. Also a file name in the outbox, hence the charset.
 */
@JvmInline
@Serializable
value class ClientMessageId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "client message id must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9._-]{1,96}$")

        fun random(): ClientMessageId = ClientMessageId("swarm-${Uuid.random()}")

        /** Null when [raw] is not a valid id (a caller-supplied `--client-message-id`). */
        fun parse(raw: String): ClientMessageId? = raw.takeIf(PATTERN::matches)?.let(::ClientMessageId)
    }
}

/** An agent as the caller named it: its id or its (case-insensitive) name. Resolved against the device's scope. */
@JvmInline
@Serializable
value class AgentRef(val value: String) {
    init {
        require(value.isNotBlank()) { "agent reference cannot be blank" }
    }

    override fun toString(): String = value
}

/** Where a send goes: an existing conversation of the agent, or a new one created for it. */
@Serializable
sealed interface ConversationTarget {
    @Serializable
    data class Existing(val id: ConversationId) : ConversationTarget

    @Serializable
    data object New : ConversationTarget
}

/** One agent in this device's scope, as `agents list` shows it. */
@Serializable
data class SwarmAgent(val id: AgentId, val name: String)
