package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.serialization.Serializable

/** Exact ownership metadata, not a hash or a claim of whole-content equality. */
@Serializable
data class TerminalOwnershipEvidence(
    val scope: TimelineScope,
    val otid: String,
    val serverId: String,
    val runId: String?,
    val seqId: Int?,
) {
    fun owns(event: TimelineEvent.Confirmed): Boolean =
        event.messageType == TimelineMessageType.ASSISTANT &&
            ((serverId.isNotBlank() && event.serverId == serverId) || (otid.isNotBlank() && event.otid == otid))

    companion object {
        fun checkpoint(scope: TimelineScope, event: TimelineEvent.Confirmed): TerminalOwnershipEvidence {
            require(event.messageType == TimelineMessageType.ASSISTANT)
            return TerminalOwnershipEvidence(scope, event.otid, event.serverId, event.runId, event.seqId)
        }
    }
}

fun interface TerminalHistoricalBodyReader {
    /** Enforce maxBytes before decoding; null means unavailable, never an empty body. */
    suspend fun read(scope: TimelineScope, owner: TerminalOwnershipEvidence, maxBytes: Long): TimelineEvent.Confirmed?
}

sealed interface TerminalEvidenceDecision {
    data class Changed(val event: TimelineEvent.Confirmed) : TerminalEvidenceDecision
    data object Unchanged : TerminalEvidenceDecision
    data class Unavailable(val reason: String) : TerminalEvidenceDecision
}

/** Called inside the engine's scoped transaction. Exceptions, including cancellation, propagate. */
suspend fun mergeOwnedTerminal(
    scope: TimelineScope,
    owner: TerminalOwnershipEvidence,
    incoming: TimelineEvent.Confirmed,
    maxBytes: Long,
    reader: TerminalHistoricalBodyReader,
): TerminalEvidenceDecision {
    require(maxBytes > 0)
    if (scope != owner.scope) return TerminalEvidenceDecision.Unavailable("scope_mismatch")
    if (!owner.owns(incoming)) return TerminalEvidenceDecision.Unavailable("ownership_unproven")
    val body = reader.read(scope, owner, maxBytes)
        ?: return TerminalEvidenceDecision.Unavailable("body_unavailable")
    if (body.messageType != TimelineMessageType.ASSISTANT ||
        body.otid != owner.otid || body.serverId != owner.serverId ||
        body.runId != owner.runId || body.seqId != owner.seqId
    ) return TerminalEvidenceDecision.Unavailable("checkpoint_changed")
    val settled = settleTerminalEvent(scope.conversationId, body, incoming)
        .copy(position = body.position, otid = body.otid)
    return if (settled == body) TerminalEvidenceDecision.Unchanged else TerminalEvidenceDecision.Changed(settled)
}
