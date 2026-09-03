package com.letta.mobile.data.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet

@Immutable
data class ReconcileAfterSendResult(
    val confirmedLocal: Boolean,
    val appendedMissing: Int,
    val confirmedServerId: String?,
    val shouldDeletePendingLocal: Boolean,
)

data class PureReconcileAfterSendResult(
    val timeline: Timeline,
    val result: ReconcileAfterSendResult,
)

fun reconcileAfterSendSnapshot(
    initial: Timeline,
    otid: String,
    serverMessages: List<LettaMessage>,
): PureReconcileAfterSendResult {
    var timeline = initial
    var confirmedLocal = false
    var confirmedServerId: String? = null
    var shouldDeletePendingLocal = false
    val existing = timeline.findByOtid(otid)
    if (existing is TimelineEvent.Local) {
        val match = serverMessages.firstOrNull { it.otid == otid }
            ?: serverMessages.lastOrNull { it.matchesRecentLocalUser(existing) }
        val confirmed = match?.toTimelineEvent(position = existing.position)
        if (confirmed != null) {
            timeline = timeline.replaceLocal(otid, confirmed)
            confirmedServerId = match.id
            confirmedLocal = true
            shouldDeletePendingLocal = true
        }
    }
    val mergeResult = timeline.mergeServerMessages(serverMessages)
    timeline = mergeResult.first
    serverMessages.lastOrNull()?.id?.let { timeline = timeline.copy(liveCursor = it) }
    return PureReconcileAfterSendResult(
        timeline,
        ReconcileAfterSendResult(confirmedLocal, mergeResult.second, confirmedServerId, shouldDeletePendingLocal),
    )
}

private fun LettaMessage.matchesRecentLocalUser(local: TimelineEvent.Local): Boolean {
    if (local.role != Role.USER || !otid.isNullOrBlank()) return false
    val confirmed = toTimelineEvent(position = 0.0)
        ?.takeIf { it.messageType == TimelineMessageType.USER }
        ?: return false
    if (confirmed.content.trim() != local.content.trim()) return false
    val messageDate = date?.let(::parseTimelineInstantOrNull) ?: return false
    val ageMillis = timelineInstantDurationMillis(local.sentAt, messageDate)
    return ageMillis in 0..CONTENT_FALLBACK_RECENCY_MS
}

fun Timeline.mergeServerMessages(
    serverMessages: List<LettaMessage>,
): Pair<Timeline, Int> {
    var timeline = this
    var merged = 0
    serverMessages.forEach { msg ->
        val pos = timeline.positionForServerMessageDate(msg)
        val confirmed = msg.toTimelineEvent(position = pos) ?: return@forEach
        if (confirmed.messageType == TimelineMessageType.TOOL_RETURN) return@forEach
        if (timeline.shouldSuppressAbandonedAssistantFragment(confirmed)) {
            Telemetry.event(
                "TimelineSync", "recentReconcile.abandonedAssistantSuppressed",
                "conversationId" to timeline.conversationId,
                "serverId" to confirmed.serverId,
                "runId" to (confirmed.runId ?: ""),
            )
            return@forEach
        }
        if (timeline.containsIdentityFor(confirmed)) return@forEach
        val existingByServerId = timeline.findByServerId(confirmed.serverId, confirmed.messageType)
        if (existingByServerId?.canReplaceIrohSyntheticLiveRow(confirmed) == true) {
            // letta-mobile-9lgfu: terminal settlement fence. The synthetic live
            // row may already hold deltas the reconciled snapshot predates; a
            // stale, non-superset final must not shrink it. Keep the promotion
            // (ids/run id come from `confirmed`) but fold the text so the
            // accumulator can only keep-or-grow.
            val settled = settleTerminalEvent(timeline.conversationId, existingByServerId, confirmed)
            timeline = timeline.replaceByServerId(settled)
            merged++
        } else if (existingByServerId == null) {
            // Exact fixed-field aliases are the only valid cross-boundary link.
            // Do not treat content, run, or turn as identity: each can describe
            // distinct assistant messages.
            val exactAliasIndex = timeline.findExactAssistantAliasIndex(confirmed)
            // Legacy content fallbacks below remain intentionally untouched for
            // non-ui-msg server records until their call sites gain exact aliases.
            // ui-msg finals bypass them and emit telemetry instead of corrupting
            // distinct rows that merely look related.
            if (confirmed.serverId.startsWith("ui-msg-") && exactAliasIndex == null) {
                Telemetry.event(
                    "TimelineSync", "recentReconcile.unresolvedCrossBoundaryIdentity",
                    "conversationId" to timeline.conversationId,
                    "serverId" to confirmed.serverId,
                    "messageType" to confirmed.messageType.name,
                    level = Telemetry.Level.WARN,
                )
                timeline = timeline.insertOrdered(confirmed)
                merged++
                return@forEach
            }
            if (exactAliasIndex != null) {
                timeline = timeline.replaceEventAt(exactAliasIndex, confirmed.copy(position = timeline.events[exactAliasIndex].position))
                merged++
                return@forEach
            }
            val prefixIndex = timeline.findRecentAssistantPrefixIndex(confirmed)
            // letta-mobile-x1xnl (SECOND path). The live Iroh stream lands the
            // assistant reply as a draft row keyed on the turn-anchored SYNTHETIC
            // otid (iroh-assistant-<turnId>) with a rotating letta-msg-* serverId,
            // then a moment later the SAME reply arrives again via this reconcile
            // snapshot carrying a REAL, DIFFERENT server id/otid and the full
            // text. serverId/otid/semantic identity all miss (different ids;
            // first-word-lag means the draft text is not even a clean prefix of
            // the full text — "Still" strands), so without this guard the
            // reconciled final is inserted as a SECOND, near-duplicate assistant
            // row. Because both rows carry the SAME REAL run id and share
            // overlapping content, they are the same in-flight message split by
            // the transport: collapse the snapshot INTO the draft row (snapshot
            // REPLACE, never append) instead of stranding a duplicate.
            val sameRunIndex = if (prefixIndex == null) {
                timeline.findRecentSameRealRunAssistantIndex(confirmed)
            } else {
                null
            }
            // letta-mobile-h30cy (THE reconcile dup, ground-truthed via
            // app-server-iroh-probe --dump-frames + admin message.list): the
            // reconciled FINAL has id==otid==ui-msg-*, run_id=NULL, and content
            // that is a SUPERSET of the streamed row (first-word-lag means it is
            // NOT byte-identical, so recentTailContainsEquivalent's exact match
            // misses; null run means findRecentSameRealRunAssistantIndex can't
            // fire). Fall back to CONTENT-SUPERSET: a recent assistant row whose
            // content is contained within the incoming full text is the same
            // in-flight reply — replace it with the fuller final (one row).
            val contentSupersetIndex = if (prefixIndex == null && sameRunIndex == null) {
                timeline.findRecentAssistantContentSupersetIndex(confirmed)
            } else {
                null
            }
            val replaceIndex = prefixIndex ?: sameRunIndex ?: contentSupersetIndex
            if (replaceIndex != null) {
                timeline = timeline.replaceEventAt(replaceIndex, confirmed.copy(position = timeline.events[replaceIndex].position))
                Telemetry.event(
                    "TimelineSync",
                    if (prefixIndex != null) "recentReconcile.assistantPrefixReplaced"
                    else "recentReconcile.assistantSameRunReplaced",
                    "conversationId" to timeline.conversationId,
                    "serverId" to confirmed.serverId,
                    "incomingLen" to confirmed.content.length,
                )
                merged++
            } else {
                timeline = timeline.insertOrdered(confirmed)
                merged++
            }
        } else if (existingByServerId.sharesRunIdentityWith(confirmed)) {
            // Same server id + message type, and at least one side lacks a
            // run id (REST /messages replies often omit run_id while the live
            // Iroh/WS row carries the real one). #780 run-scoped identity keys
            // no longer overlap in that case, so without this guard the
            // reconciled copy re-inserts and the reply renders twice. Only a
            // REAL run-id mismatch on both sides (recycled server id across
            // runs) may append.
            return@forEach
        } else {
            timeline = timeline.insertOrdered(confirmed)
            merged++
        }
    }
    return timeline to merged
}

private const val CONTENT_FALLBACK_RECENCY_MS = 2 * 60 * 1000L

private fun Timeline.replaceEventAt(index: Int, event: TimelineEvent.Confirmed): Timeline {
    val updated = events.toMutableList()
    updated[index] = event
        val updatedEvents = updated.toPersistentList()
        return copy(
            events = updatedEvents,
            stablePrefixVersion = stablePrefixVersion + 1,
            residentOtids = updatedEvents.mapTo(mutableSetOf()) { it.otid }.toPersistentSet(),
            invariantsKnown = true,
        )
}

private fun Timeline.findExactAssistantAliasIndex(incoming: TimelineEvent.Confirmed): Int? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    for (index in events.indices.reversed()) {
        val event = events[index] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != TimelineMessageType.ASSISTANT) continue
        if (event.serverId == incoming.serverId || event.otid == incoming.otid ||
            event.serverId == incoming.otid || event.otid == incoming.serverId
        ) return index
    }
    return null
}

private fun Timeline.findRecentAssistantPrefixIndex(incoming: TimelineEvent.Confirmed): Int? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    val incomingText = incoming.content.trim()
    if (incomingText.length < 2) return null
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    for (index in events.size - 1 downTo start) {
        val event = events[index] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != TimelineMessageType.ASSISTANT) continue
        if (event.serverId == incoming.serverId) continue
        val existingText = event.content.trim()
        if (existingText.isBlank()) continue
        if (incomingText.length > existingText.length && incomingText.startsWith(existingText)) {
            return index
        }
    }
    return null
}

/**
 * letta-mobile-x1xnl (SECOND path). Find a recent assistant row that is the
 * live-streamed draft of the SAME in-flight reply as [incoming] (the reconcile
 * snapshot), so the snapshot can REPLACE it instead of appending a duplicate.
 *
 * A row qualifies when it shares the SAME REAL run id with [incoming] and its
 * content overlaps — one is a prefix, suffix, or substring of the other. This
 * catches the on-device symptom where the draft's first word lags and strands
 * ("Still kicking…" reconciled vs " kicking…" draft), which the strict
 * prefix-only [findRecentAssistantPrefixIndex] misses because the draft is a
 * SUFFIX (not a prefix) of the full text.
 *
 * Constraints that keep this from collapsing genuinely-distinct rows:
 *  - Both sides must carry a real (non-`iroh-run-*`) run id, and it must MATCH.
 *    Distinct assistant messages within one run are rare (tool-mediated), and
 *    even then only collapse when their text overlaps as a prefix/suffix, which
 *    independent replies do not.
 *  - The synthetic-live→real-run replacement is already handled by
 *    [canReplaceIrohSyntheticLiveRow]/id match above; here the run ids are BOTH
 *    real, so this only fires once the transport has promoted the row's run id.
 */
private fun Timeline.findRecentSameRealRunAssistantIndex(incoming: TimelineEvent.Confirmed): Int? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() && !it.isReconcileSyntheticRunId() } ?: return null
    val incomingText = incoming.content.trim()
    if (incomingText.isBlank()) return null
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    for (index in events.size - 1 downTo start) {
        val event = events[index] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != TimelineMessageType.ASSISTANT) continue
        if (event.serverId == incoming.serverId) continue
        val existingRunRaw = event.runId?.takeIf { it.isNotBlank() } ?: continue
        // Qualify the candidate's run id. Either (a) it shares the SAME REAL run
        // id as [incoming] — two live-stream fragments of one reply — or (b) it is
        // a SYNTHETIC iroh live/observer placeholder (iroh-run-* / iroh-observer-run-*)
        // that this REAL-run reconciled copy PROMOTES. Case (b) is the same rule
        // canReplaceIrohSyntheticLiveRow already applies on the server-id-match
        // branch, extended here to the different-server-id reconcile: the passive
        // observer stamps a throwaway synthetic run on the fanned-out reply, then
        // the post-send reconcile refetches that same reply under its CANONICAL
        // server id + real run id. serverId/exact-content/prefix all miss, and this
        // guard used to skip the synthetic row (`!isReconcileSyntheticRunId`), so
        // the reconciled copy appended as a DUPLICATE. The content-overlap check
        // below still discriminates, so an unrelated message never collapses.
        val qualifies = when {
            existingRunRaw.isReconcileSyntheticRunId() -> true
            else -> existingRunRaw == incomingRunId
        }
        if (!qualifies) continue
        val existingText = event.content.trim()
        if (existingText.isBlank()) continue
        if (existingText == incomingText ||
            existingText.contains(incomingText) ||
            incomingText.contains(existingText)
        ) {
            return index
        }
    }
    return null
}

/**
 * letta-mobile-h30cy: content-superset fallback for the reconcile duplicate when
 * NO id/otid/run match is possible. The reconciled final (message.list) has a
 * ui-msg-* id/otid and NULL run id, so it cannot be matched by identity; and its
 * text differs slightly from the streamed row (first-word-lag), so the exact
 * recentTailContainsEquivalent misses. Match a recent assistant row whose trimmed
 * content is CONTAINED in the incoming full text (the incoming is the superset /
 * fuller final of that in-flight reply). Guarded to a meaningful length and the
 * recent tail so distinct short messages don't coincidentally collapse.
 */
private fun Timeline.findRecentAssistantContentSupersetIndex(incoming: TimelineEvent.Confirmed): Int? {
    if (incoming.messageType != TimelineMessageType.ASSISTANT) return null
    // #826 review (P1) + headless-replay guard: only the RECONCILE final has this
    // signature — a NULL (or reconcile-synthetic) run id. The live stream frames
    // and normal replayed assistant messages carry a real run id, so restricting
    // to a blank/synthetic incoming run id ensures we ONLY collapse the ui-msg
    // reconcile duplicate and never a legitimate prefix/superset relationship
    // between two real-run messages (e.g. the cm-prefix/cm-full prefix-orphan
    // diagnostic case).
    // #826 review (P1): the RECONCILE final is discriminated by its NULL (or
    // reconcile-synthetic) run id — the streamed rows and normal replayed
    // assistant messages carry a REAL run id. Requiring incoming.runId to be
    // blank/synthetic is what keeps this from ever collapsing a legitimate
    // prefix/superset relationship between two REAL-run messages (the
    // cm-prefix/cm-full prefix-orphan diagnostic case, run_id=run-1).
    val incomingRun = incoming.runId?.takeIf { it.isNotBlank() }
    if (incomingRun != null && !incomingRun.isReconcileSyntheticRunId()) return null
    val incomingText = incoming.content.trim()
    if (incomingText.length < 3) return null
    // h30cy resurface fix: do NOT also require the match to be the liveCursor row.
    // At reconcile time the liveCursor has often moved off (or been cleared to) the
    // streamed reply, so the liveCursor gate silently missed the duplicate. The
    // null-run gate above is the correct and sufficient discriminator; scan the
    // recent tail for the streamed row this null-run final is the fuller superset
    // of, and collapse it. Prefer the LONGEST such existing match (the streamed
    // reply row) so we never pick a coincidentally-contained shorter earlier row.
    // #827 review (P1/Major): correlate the candidate before replacing. Without a
    // discriminator, an unrelated OLDER assistant reply whose text merely happens
    // to be a substring of this null-run final would be overwritten. Two guards:
    //  (1) the candidate must be a STREAMED row — it carries a REAL run id
    //      (local-run-*/provider run). A reconciled/null-run older message is NOT a
    //      streamed reply awaiting its final, so it is never a collapse target.
    //  (2) match only the MOST RECENT (highest-index) qualifying streamed row (the
    //      one currently awaiting its reconcile final), never an earlier one — and
    //      require the incoming final to strictly contain it. This prevents
    //      selecting a distinct older reply that is coincidentally contained.
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    val normalizedIncoming = incomingText.filterLettersAndDigits()
    for (index in events.size - 1 downTo start) {
        val event = events[index] as? TimelineEvent.Confirmed ?: continue
        if (event.messageType != TimelineMessageType.ASSISTANT) continue
        if (event.serverId == incoming.serverId) continue
        val existingText = event.content.trim()
        if (existingText.length < 3) continue
        // Guard (1): candidate must be a live-STREAMED row, never a reconciled one.
        // A null run id marks a reconciled/replayed row — not a streamed reply
        // awaiting its final — so it is never a collapse target. A SYNTHETIC run id
        // (iroh-run-* / iroh-observer-run-*) IS a streamed row: the send/observer
        // path stamps a throwaway placeholder the real run later supersedes, so it
        // MUST stay eligible. Excluding synthetic here stranded the passive-observer
        // duplicate whenever its reconcile final arrived with a null run id (the
        // admin/HTTP message.list shape).
        val candidateRun = event.runId?.takeIf { it.isNotBlank() }
        if (candidateRun == null) continue
        // Guard (2): first (most recent) streamed row strictly contained by the
        // incoming final IS the reply being finalized. Return it immediately.
        // If not contained, continue scanning backwards — there may be an older
        // streamed row (from an earlier turn) that IS contained and should be
        // collapsed (e.g., delayed reconcile final arriving after a newer turn
        // has already started).
        if (incomingText != existingText &&
            incomingText.contains(existingText) &&
            incomingText.length > existingText.length
        ) {
            return index
        }
        // letta-mobile-qq9sd: the streamed row can be MANGLED relative to its
        // final, not just first-word-lagged — dropped stream fragments leave it
        // missing scattered punctuation too ("…streaming path The…" vs
        // "…streaming path. The…"), so the strict containment above misses and
        // the null-run final re-appends as a near-full-message duplicate. Retry
        // the SAME containment relation on letters/digits only: punctuation and
        // whitespace divergence never distinguishes two genuinely different
        // replies, and the min-length gate keeps short coincidences out. All
        // row-qualification guards above still apply unchanged.
        val normalizedExisting = existingText.filterLettersAndDigits()
        if (normalizedExisting.length >= RECONCILE_MANGLED_SUPERSET_MIN_CHARS &&
            normalizedIncoming.contains(normalizedExisting)
        ) {
            return index
        }
    }
    return null
}

// letta-mobile-qq9sd: minimum letters+digits length before the mangled-superset
// fallback may fire. Short assistant rows ("Nice", "Got it") are either caught
// by the strict prefix/containment paths or too coincidence-prone to collapse.
private const val RECONCILE_MANGLED_SUPERSET_MIN_CHARS = 12

private fun String.filterLettersAndDigits(): String = filter { it.isLetterOrDigit() }

// letta-mobile-j98r5.1: reconcile classifies Iroh client-synthesized run-id
// placeholders via the SINGLE canonical predicate shared with the stream
// reducer ([isIrohSyntheticRunId] in TimelineStreamReducer.kt). Both the
// send/stream path (`iroh-run-*`) and the passive OBSERVER path
// (`iroh-observer-run-*`, IrohChannelTransport.kt:414) stamp throwaway
// placeholders that the real server run id later supersedes, so both must be
// classified as synthetic. Delegating to the one helper keeps reconcile and
// stream reduction from drifting; this is a pure downstream CLASSIFICATION
// change (the stamped values are left untouched at the transport).
private fun String.isReconcileSyntheticRunId(): Boolean = isIrohSyntheticRunId()

private fun TimelineEvent.Confirmed.canReplaceIrohSyntheticLiveRow(
    incoming: TimelineEvent.Confirmed,
): Boolean {
    val existingRunId = runId?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() }
    return messageType in setOf(TimelineMessageType.ASSISTANT, TimelineMessageType.REASONING) &&
        existingRunId?.isReconcileSyntheticRunId() == true &&
        incomingRunId != null &&
        !incomingRunId.isReconcileSyntheticRunId()
}

/**
 * Content-level dedupe for reconcile copies whose ids can never match the live
 * rows (HTTP backend mints ui-msg-* ids; live Iroh rows carry letta-msg-*).
 * Scans the recent tail for a row of the same message type with identical
 * trimmed content. Bounded to the tail window so long histories stay O(1)-ish
 * per reconcile and legitimately repeated OLD messages are not collapsed.
 *
 * letta-mobile-20tat (P1): enhanced to also check Local user events so the
 * optimistic send row and its reconciled disk twin collapse even when the
 * server doesn't echo otid (common over Iroh).
 */
private const val RECONCILE_CONTENT_DEDUPE_TAIL = 30

private fun Timeline.recentTailContainsEquivalent(incoming: TimelineEvent.Confirmed): Boolean {
    if (incoming.messageType != TimelineMessageType.ASSISTANT &&
        incoming.messageType != TimelineMessageType.USER &&
        incoming.messageType != TimelineMessageType.REASONING
    ) {
        return false
    }
    val incomingContent = incoming.content.trim()
    if (incomingContent.isEmpty()) return false
    val start = (events.size - RECONCILE_CONTENT_DEDUPE_TAIL).coerceAtLeast(0)
    for (i in events.size - 1 downTo start) {
        // letta-mobile-20tat: check both Confirmed AND Local user events
        when (val event = events[i]) {
            is TimelineEvent.Confirmed -> {
                if (event.messageType != incoming.messageType) continue
                if (event.content.trim() == incomingContent) return true
            }
            is TimelineEvent.Local -> {
                // Only dedupe user messages against Local rows (assistant/
                // reasoning never appear as Local in production flow)
                if (incoming.messageType == TimelineMessageType.USER &&
                    event.role == Role.USER &&
                    event.content.trim() == incomingContent
                ) {
                    return true
                }
            }
        }
    }
    return false
}

/**
 * True when an existing row with the same server id should absorb the incoming
 * reconciled copy instead of appending a duplicate. Matching real run ids, or
 * either side missing a run id (REST reconcile responses often omit run_id),
 * count as the same logical message. Only two DIFFERENT real run ids — the
 * recycled-server-id-across-runs case from #780 — are treated as distinct.
 */
private fun TimelineEvent.Confirmed.sharesRunIdentityWith(
    incoming: TimelineEvent.Confirmed,
): Boolean {
    val existingRunId = runId?.takeIf { it.isNotBlank() }
    val incomingRunId = incoming.runId?.takeIf { it.isNotBlank() }
    if (existingRunId == null || incomingRunId == null) return true
    return existingRunId == incomingRunId
}

fun Timeline.positionForServerMessageDate(message: LettaMessage): Double {
    val messageDate = message.date?.let { parseTimelineInstantOrNull(it) }
        ?: return nextLocalPosition()
    val nextIndex = events.indexOfFirst { event ->
        val eventDate = (event as? TimelineEvent.Confirmed)?.date ?: return@indexOfFirst false
        compareTimelineInstants(eventDate, messageDate) > 0
    }
    if (nextIndex < 0) return nextLocalPosition()

    val nextPosition = events[nextIndex].position
    val previousPosition = events.getOrNull(nextIndex - 1)?.position
    return when {
        previousPosition == null -> nextPosition - 1.0
        nextPosition > previousPosition -> previousPosition + ((nextPosition - previousPosition) / 2.0)
        else -> previousPosition + 0.000001
    }
}



suspend fun reconcileForExternalRun(
    runId: String,
    reconcileRecentMessagesFromServer: suspend (String, String, Boolean) -> Unit,
) {
    reconcileRecentMessagesFromServer("externalRunReconcile", runId, true)
}
