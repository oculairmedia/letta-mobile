package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.request.VaultItemName
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A service answer: a value or a refusal with a stable code. */
sealed interface SecretServiceResult<out T> {
    /** The call succeeded with [value]. */
    data class Ok<out T>(
        /** What the call answered. */
        val value: T,
    ) : SecretServiceResult<T>

    /** The call was refused with [error]; [problem] is set for [SecretServiceError.INVALID_REQUEST]. */
    data class Refused(
        /** Why it was refused. */
        val error: SecretServiceError,
        /** What is wrong with the agent's request, for [SecretServiceError.INVALID_REQUEST] only. */
        val problem: SecretRequestProblem? = null,
    ) : SecretServiceResult<Nothing>
}

/**
 * What the host allows agents to request: an optional item scope every requested item must lie in
 * (null allows any well-formed item), how long a request stays pending, and how many requests one
 * agent may have pending at once.
 */
data class SecretRequestPolicy(
    /** The item every request must name or lie under; null for no restriction. */
    val itemScope: VaultItemName? = null,
    /** How long a request stays pending. */
    val ttl: Duration = SecretRequestLimits.DEFAULT_TTL_MS.milliseconds,
    /** The most pending requests per agent. */
    val maxPendingPerAgent: Int = SecretRequestLimits.MAX_PENDING_PER_AGENT,
)

/** Where request ids get their randomness; it must be cryptographically secure. */
fun interface SecretRandomSource {
    /** [size] fresh random bytes. */
    fun nextBytes(size: Int): ByteArray

    /** The default source. */
    companion object {
        /** A source backed by [SecureRandom]. */
        fun secure(): SecretRandomSource {
            val random = SecureRandom()
            return SecretRandomSource { size -> ByteArray(size).also(random::nextBytes) }
        }
    }
}

/** The service's injected policy, clock and randomness. */
data class SecretRequestSettings(
    /** What agents may request. */
    val policy: SecretRequestPolicy = SecretRequestPolicy(),
    /** The clock for creation, expiry and settlement times. */
    val clock: Clock = Clock.System,
    /** The source of request ids. */
    val random: SecretRandomSource = SecretRandomSource.secure(),
)
