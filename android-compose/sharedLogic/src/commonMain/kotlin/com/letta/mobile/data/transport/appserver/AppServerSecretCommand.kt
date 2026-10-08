package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bzvro.25: an agent's secrets vault (letta-code `secret_list`, `secret_apply`).
 *
 * Both carry plaintext values on the wire: `secret_list_response` returns every key with its
 * value, and [SecretApply.set] sends new ones. Values are stored only by the server; nothing here
 * may log, persist or export them, which is why [SecretApply.toString] is redacted and the
 * responses stay untyped (see [AppServerWorkspaceCommand]).
 */
@Serializable
sealed interface AppServerSecretCommand : AppServerWorkspaceCommand {
    val agentId: String

    /** Refreshes the server's secrets cache and answers with every key and its value. */
    @Serializable
    @SerialName("secret_list")
    data class SecretList(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
    ) : AppServerSecretCommand {
        override val responseType: String get() = "secret_list_response"
        override val isRead: Boolean get() = true
    }

    /**
     * One atomic batch: the server computes (current ∪ [set]) ∖ [unset] and writes it in a single
     * call. Keys are upper-cased server-side; a key in both lists ends up removed.
     */
    @Serializable
    @SerialName("secret_apply")
    data class SecretApply(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        val set: Map<String, String> = emptyMap(),
        val unset: List<String> = emptyList(),
    ) : AppServerSecretCommand {
        override val responseType: String get() = "secret_apply_response"
        override val isRead: Boolean get() = false

        /** Never the values: a command that reaches a log or an exception message must not leak them. */
        override fun toString(): String =
            "SecretApply(requestId=$requestId, agentId=$agentId, set=${set.keys}, unset=$unset)"
    }
}
