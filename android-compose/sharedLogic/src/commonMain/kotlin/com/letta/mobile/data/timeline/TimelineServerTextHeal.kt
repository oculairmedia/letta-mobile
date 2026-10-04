package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-4vtng.2: server text is authoritative for a settled confirmed row.
 *
 * Live writes (and the pre-#1768 double-stamped cumulative snapshots) can leave a garbled
 * assistant/reasoning body persisted under an identity that reconcile already knows, and
 * `mergeServerMessages` skips known identities, so the bad text would stick forever. When the
 * fetched server row matches a held confirmed row by identity and its text differs, the server
 * text replaces the held text. No pattern detection: replacement is purely server-authoritative.
 *
 * Never touched: a row on a synthetic live run (still streaming, handled by the iroh promotion
 * path), a row whose text is ahead of a stale server copy (the server text is a strict prefix of
 * the local text, i.e. the stream is still growing), and a blank server body. Unchanged rows
 * return `this`, so no emission and no persistence write follow.
 */
internal fun Timeline.withConfirmedTextHealedBy(incoming: TimelineEvent.Confirmed): Timeline {
    if (incoming.messageType !in HEALABLE_TEXT_TYPES || incoming.content.isBlank()) return this
    val existing = findByServerId(incoming.serverId, incoming.messageType)
        ?: (findByOtid(incoming.otid) as? TimelineEvent.Confirmed)?.takeIf { it.messageType == incoming.messageType }
        ?: return this
    if (!existing.shouldHealTextFrom(incoming)) return this
    Telemetry.event(
        "TimelineSync", "recentReconcile.confirmedTextHealed",
        "conversationId" to conversationId,
        "serverId" to existing.serverId,
        "messageType" to existing.messageType.name,
        "oldLen" to existing.content.length,
        "newLen" to incoming.content.length,
    )
    return replaceByServerId(existing.copy(content = incoming.content))
}

private val HEALABLE_TEXT_TYPES = setOf(TimelineMessageType.ASSISTANT, TimelineMessageType.REASONING)

private fun TimelineEvent.Confirmed.shouldHealTextFrom(incoming: TimelineEvent.Confirmed): Boolean {
    if (content == incoming.content) return false
    if (runId?.isIrohSyntheticRunId() == true) return false
    val serverBehindLiveStream = content.startsWith(incoming.content)
    return !serverBehindLiveStream
}
