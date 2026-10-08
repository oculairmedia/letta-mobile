package com.letta.mobile.data.secrets

import androidx.compose.runtime.Immutable

/**
 * A secret's plaintext, held only in memory (letta-mobile-bzvro.25). Its [toString] never shows
 * the value, so a secret that reaches a log line, an exception message or a state dump stays
 * hidden; the one way to read it is [reveal], which the vault calls only to draw a value the user
 * explicitly asked to see, or to send it to the server.
 */
@Immutable
class SecretValue(private val plaintext: String) {
    val isBlank: Boolean get() = plaintext.isBlank()

    fun reveal(): String = plaintext

    override fun equals(other: Any?): Boolean = other is SecretValue && other.plaintext == plaintext

    override fun hashCode(): Int = plaintext.hashCode()

    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED: String = "SecretValue(<redacted>)"

        /** What a hidden value draws as: a fixed width, so the mask does not leak the length. */
        const val MASK: String = "••••••••"

        val Empty: SecretValue = SecretValue("")
    }
}

/** One key in an agent's vault. */
@Immutable
data class AgentSecret(
    val key: String,
    val value: SecretValue,
)

/** One batch for the server: keys to add or replace, and keys to remove. */
@Immutable
data class AgentSecretChanges(
    val set: Map<String, SecretValue> = emptyMap(),
    val unset: Set<String> = emptySet(),
)

/** A vault request the server refused or could not answer; [message] never carries a value. */
class AgentSecretsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The port the vault reads and writes through (letta-mobile-bzvro.25). Values live on the server
 * only: implementations must not cache, persist or log them.
 */
interface AgentSecretsSource {
    /** Every secret of [agentId], sorted by key. */
    suspend fun list(agentId: String): List<AgentSecret>

    /** Applies [changes] atomically; returns the key names afterwards. */
    suspend fun apply(agentId: String, changes: AgentSecretChanges): List<String>
}

/** Which hosts show the per-agent secrets vault (open question 5 of the parity analysis). */
@Immutable
data class AgentSecretsFeature(val enabled: Boolean) {
    companion object {
        /** Desktop users manage their agents' tool keys here. */
        val Desktop = AgentSecretsFeature(enabled = true)

        /** Off on phones until the decision on exposing plaintext keys there is made. */
        val Android = AgentSecretsFeature(enabled = false)
    }
}

/** Secret names are environment-variable style; the server upper-cases them. */
object AgentSecretKeys {
    private val VALID = Regex("^[A-Z_][A-Z0-9_]*$")

    fun normalize(key: String): String = key.trim().uppercase()

    fun isValid(key: String): Boolean = VALID.matches(normalize(key))
}
