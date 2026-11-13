package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.request.RequestingAgent
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
 * Which items an agent may name in a request. Every item an agent requests must equal the root
 * [rootFor] answers for it or lie under it by whole segments; a request outside it is refused with
 * [SecretRequestProblem.ITEM_OUTSIDE_SCOPE].
 *
 * There is no unrestricted scope: [PerAgent] is the default, and anything wider is a [Shared] root
 * the host owner names on purpose.
 */
sealed interface SecretItemScope {
    /** The root [agent]'s items must lie in, or null when [agent] may name no item at all. */
    fun rootFor(agent: RequestingAgent): VaultItemName?

    /**
     * Each agent may name only items under `agents/<its own agent id>`, the id taken from the
     * verified caller, so one agent can never name (and so never overwrite) another agent's items.
     * An agent id that is not a single item segment gets no root, so every item is refused.
     */
    data object PerAgent : SecretItemScope {
        /** The first segment of every per-agent root. */
        const val PREFIX: String = "agents"

        override fun rootFor(agent: RequestingAgent): VaultItemName? {
            val id = agent.agentId.value
            return VaultItemName("$PREFIX/$id").takeIf { '/' !in id && it.isWellFormed }
        }
    }

    /**
     * Every agent may name items under [root]: a wider scope that only the host owner may choose,
     * because every agent then shares the items under it (a request still never replaces an
     * existing value unless the person was told it would when the request was made).
     */
    data class Shared(
        /** The item every request must name or lie under. */
        val root: VaultItemName,
    ) : SecretItemScope {
        init {
            require(root.isWellFormed) { "A shared item scope must be a well-formed item name" }
        }

        override fun rootFor(agent: RequestingAgent): VaultItemName = root
    }
}

/**
 * What the host allows agents to request: which items ([itemScope], per agent unless the host
 * owner chose otherwise), how long a request stays pending, and how many requests one agent may
 * have pending at once.
 */
data class SecretRequestPolicy(
    /** Which items agents may name. */
    val itemScope: SecretItemScope = SecretItemScope.PerAgent,
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
