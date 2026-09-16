package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Serialized legacy decision fields, with no mutable collection retained at the boundary. */
data class AbandonedFragmentEvidence private constructor(
    val scope: TimelineScope,
    val serializedDecisions: String,
) {
    fun suppresses(scope: TimelineScope, event: TimelineEvent.Confirmed): Boolean {
        if (scope != this.scope || event.messageType != TimelineMessageType.ASSISTANT) return false
        val decisions = Json.decodeFromString<List<AbandonedAssistantFragmentSuppression>>(serializedDecisions)
        return event.toAbandonedAssistantFragmentSuppression() in decisions
    }

    companion object {
        fun checkpoint(scope: TimelineScope, timeline: Timeline): AbandonedFragmentEvidence {
            require(scope.conversationId == timeline.conversationId)
            return AbandonedFragmentEvidence(scope, Json.encodeToString(timeline.abandonedAssistantFragmentSuppressions.toList()))
        }
    }
}
