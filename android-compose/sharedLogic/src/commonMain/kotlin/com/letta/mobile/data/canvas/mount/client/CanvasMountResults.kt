package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlin.jvm.JvmInline

/**
 * The conversation a canvas mount is about: [conversationId] of agent [agentId]. A mount is held by
 * the host per conversation, so the same conversation shows the same board on every paired device.
 */
data class ConversationCanvasTarget(val agentId: AgentId, val conversationId: ConversationId) {
    /** The conversation's own board, the one it shows when nothing else is mounted. */
    val ownCanvas: CanvasId get() = CanvasId.forConversation(conversationId)

    companion object {
        /** The target for a page that may not have a conversation (a new chat) or agent yet; null then. */
        fun of(agentId: AgentId?, conversationId: ConversationId?): ConversationCanvasTarget? {
            val agent = agentId?.takeIf { it.value.isNotBlank() } ?: return null
            val conversation = conversationId?.takeIf { it.value.isNotBlank() } ?: return null
            return ConversationCanvasTarget(agent, conversation)
        }
    }
}

/** Where the host found a mounted canvas: this conversation's own binding, or its agent's default. */
enum class CanvasMountSource {
    /** The conversation's own binding. */
    Binding,

    /** The agent's default, used when the conversation has no binding. */
    Default,
}

/** Who a mount reaches. */
enum class CanvasMountReach {
    /** Held by the host: every paired device and the agent's canvas tools see it. */
    Host,

    /** Held on this device for this app session only; the agent's canvas tools keep the conversation's own canvas. */
    Device,
}

/** How sure the host is that a mount it reported survives a power cut. */
enum class CanvasMountDurability {
    /** Written and confirmed on disk. */
    Durable,

    /** Written, but the host could not confirm the directory entry (Windows); a lost write is set again. */
    EntryUnconfirmed,

    /** The host did not say, or the answer was inferred from a later read. */
    Unknown,
}

/** The fixed reason a host gave for being unable to answer; never a path, message or id. */
@JvmInline
value class CanvasMountUnavailableReason(val value: String) {
    companion object {
        /** The host does not offer canvas mounts at all. */
        val Unsupported: CanvasMountUnavailableReason = CanvasMountUnavailableReason("unsupported")

        /** The host's answer stayed unknown after asking again. */
        val StillUnknown: CanvasMountUnavailableReason = CanvasMountUnavailableReason("outcome_unknown")
    }
}

/** The field a refused input named, as the host's refusal spells it (`agent_id`, `canvas_id`). */
@JvmInline
value class CanvasMountField(val value: String)

/** Why the host refused one field, as the host's refusal spells it (`blank`, `too_long`). */
@JvmInline
value class CanvasMountInputCode(val value: String)

/** Why a mount call did not give an answer. */
sealed interface CanvasMountProblem {
    /** Not permitted. Carries nothing, so a refused caller learns nothing about what is stored. */
    data object Denied : CanvasMountProblem

    /** The host refused [field] for [code]; nothing was read or written. */
    data class InvalidInput(val field: CanvasMountField, val code: CanvasMountInputCode) : CanvasMountProblem

    /** The host could not answer now, for [reason]. Nothing is known about the stored mount. */
    data class Unavailable(val reason: CanvasMountUnavailableReason) : CanvasMountProblem

    /** A write may or may not have been applied; the caller must resolve again to learn which. */
    data object OutcomeUnknown : CanvasMountProblem
}

/** The answer to "which canvas is mounted for this conversation". */
sealed interface CanvasMountLookup {
    /** [canvas] is mounted, from [source], with the host's [durability]. */
    data class Found(
        val canvas: CanvasId,
        val source: CanvasMountSource,
        val durability: CanvasMountDurability = CanvasMountDurability.Unknown,
    ) : CanvasMountLookup

    /** Nothing is mounted: the conversation shows its own board. */
    data object None : CanvasMountLookup

    /** No answer, for [problem]. */
    data class Failed(val problem: CanvasMountProblem) : CanvasMountLookup
}

/** The answer to a mount or unmount. */
sealed interface CanvasMountChange {
    /** The conversation now shows [canvas] (its own board after an unmount). */
    data class Applied(
        val canvas: CanvasId,
        val durability: CanvasMountDurability = CanvasMountDurability.Unknown,
    ) : CanvasMountChange

    /** Not applied, or not known to be, for [problem]. */
    data class Failed(val problem: CanvasMountProblem) : CanvasMountChange
}
