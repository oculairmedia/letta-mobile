package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestRefusal
import kotlinx.coroutines.flow.Flow

/** Whose pending requests a client lists. */
sealed interface SecretRequestScope {
    /** Every pending request the host holds for this person, whichever agent asked. */
    data object Everyone : SecretRequestScope

    /** Only the pending requests made by [agent]. */
    data class Agent(
        /** The requesting agent. */
        val agent: AgentId,
    ) : SecretRequestScope
}

/** What the host answered to a submission or a decline. None of it carries a value. */
sealed interface SecretClientOutcome {
    /** The request settled; [receipt] is the receipt the agent gets too. */
    data class Settled(
        /** The receipt: status, item, field and, for a failure, the fixed reason. */
        val receipt: SecretRequestReceipt,
    ) : SecretClientOutcome

    /** The host refused the call with a stable code; see [SecretRequestRefusal]. */
    data class Refused(
        /** Why it was refused. */
        val refusal: SecretRequestRefusal,
    ) : SecretClientOutcome

    /**
     * The call did not reach the host, or its answer did not come back. Whether the host saw it is
     * unknown, so a client never re-sends on its own: the person has to type the value again.
     */
    data object Unreachable : SecretClientOutcome
}

/**
 * The human side of the secret request flow, as the shared UI sees it. The secure form depends on
 * this port only; the implementation that talks to the host (a dedicated admin RPC route, never a
 * conversation or a tool call) is a later stage and is not part of this module.
 *
 * Implementations must keep the contract's rules: [pending] carries metadata only, [submit] sends
 * [SecretInput] straight to the host and nowhere else, and neither call logs, records or retries
 * a value.
 */
interface SecretRequestClient {
    /** The requests in [scope] that are still waiting for an answer, as the host lists them; never a value. */
    fun pending(scope: SecretRequestScope): Flow<List<SecretRequestRecord>>

    /**
     * Answers [requestId] with [value]. The caller owns [value] and wipes it when this returns, on
     * every path; an implementation must not keep it or a copy of it.
     */
    suspend fun submit(requestId: SecretRequestId, value: SecretInput): SecretClientOutcome

    /** Declines [requestId]: the agent gets a cancelled receipt. */
    suspend fun decline(requestId: SecretRequestId): SecretClientOutcome
}
