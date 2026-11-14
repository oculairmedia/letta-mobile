package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

/**
 * The verified actor of a canvas mount call. [AuthorizedCanvasMounts] requires one for every call.
 *
 * The only legitimate source is the identity the host established for the serving connection (for an
 * Iroh peer, the authenticated endpoint the connection itself verified), never a request field. Agent
 * and conversation ids are the TARGETS a call addresses, never the actor, so this type is deliberately
 * not built from them and has no public constructor: [fromVerifiedConnection] names that contract at
 * every construction site. [toString] never prints the identity.
 */
@JvmInline
value class CanvasMountPrincipal private constructor(
    /** The host-verified connection identity the [CanvasMountAuthority] decides on. */
    val endpoint: String,
) {
    override fun toString(): String = "CanvasMountPrincipal(verified)"

    /** Construction from verified host connection identity only. */
    companion object {
        /**
         * Wraps the identity the host verified for the serving connection. Returns null for a blank
         * identity or one longer than [AuthorizedCanvasMounts.MAX_ID_BYTES] UTF-8 bytes.
         */
        fun fromVerifiedConnection(endpoint: String): CanvasMountPrincipal? =
            endpoint.takeIf(::isBoundedMountId)?.let(::CanvasMountPrincipal)
    }
}

/** The mount record a call addresses. Both shapes are targets, never the caller's identity. */
sealed interface CanvasMountScope {
    /** The agent that owns the addressed record. */
    val agent: AgentId

    /** The agent's default mount, used where a conversation has no binding of its own. */
    data class AgentDefault(override val agent: AgentId) : CanvasMountScope

    /** One agent/conversation binding. */
    data class Conversation(
        override val agent: AgentId,
        /** The conversation whose binding is addressed. */
        val conversation: ConversationId,
    ) : CanvasMountScope
}

/** Bundles the verified [principal] with the [scope] it addresses, so neither travels alone. */
data class CanvasMountRequest<out S : CanvasMountScope>(
    /** The verified actor. */
    val principal: CanvasMountPrincipal,
    /** The addressed target. */
    val scope: S,
)

/** One authorization answer. Only [ALLOW] permits; anything else, or a throwing authority, denies. */
enum class CanvasMountDecision {
    /** The principal may proceed. */
    ALLOW,

    /** The principal may not proceed. */
    DENY,

    /** The authority cannot decide; treated exactly as [DENY]. */
    ABSTAIN,
}

/**
 * Host-supplied policy behind [AuthorizedCanvasMounts]. The questions are distinct and asked in a
 * fixed order; an implementation must answer each on its own terms and must not infer one from another.
 * Default deny: [CanvasMountDecision.ABSTAIN], [CanvasMountDecision.DENY] and any exception all deny.
 */
interface CanvasMountAuthority {
    /** May this principal address this agent/conversation (or agent default) record at all? */
    suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision

    /** May this principal store [proposed] in the addressed record? */
    suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId): CanvasMountDecision

    /** May this principal learn that [winner] is the canvas the addressed record holds? */
    suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId): CanvasMountDecision

    /** May this principal remove the addressed record (no canvas is proposed)? Denies unless an authority decides. */
    suspend fun mayClear(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision = CanvasMountDecision.DENY
}

internal fun isBoundedMountId(value: String): Boolean =
    value.isNotBlank() && value.encodeToByteArray().size <= AuthorizedCanvasMounts.MAX_ID_BYTES
