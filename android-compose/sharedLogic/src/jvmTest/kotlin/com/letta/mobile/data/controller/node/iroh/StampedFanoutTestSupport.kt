package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.TurnStreamIdentity
import com.letta.mobile.data.runtime.stampPayload
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import java.util.Collections
import java.util.WeakHashMap

private val identityByFanout = Collections.synchronizedMap(WeakHashMap<ConversationTurnFanout, TurnStreamIdentity>())

/**
 * Feeds [payload] to this fanout the way production does: stamped by the turn's [TurnStreamIdentity]
 * (one per fanout here, as one fanout serves one turn) before the fanout sees it.
 */
internal suspend fun ConversationTurnFanout.onStampedDraft(payload: RuntimeEventPayload, runId: RunId? = null): Boolean {
    val identity = identityByFanout.getOrPut(this) {
        var minted = 0
        TurnStreamIdentity("turn-1") { "lm-${++minted}" }
    }
    val stamped = identity.stampPayload(payload) ?: return false
    return onDraft(stamped, runId)
}
