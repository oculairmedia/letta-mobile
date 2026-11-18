package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildFoldedChatRenderModel
import com.letta.mobile.data.chat.projection.foldMonitorEvents
import com.letta.mobile.data.chat.projection.hasNoRenderableContent
import com.letta.mobile.data.chat.projection.isMonitorWatcher
import com.letta.mobile.data.chat.projection.scopeMessagesToAgent
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.chat.tools.bundled.SkillToolPresenter
import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Canonical ownership, shared by settled and live projection; never inferred from message content. */
data class TimelineProjectionContext(val scope: TimelineScope, val ownAgentId: String?)

sealed interface TimelineRunBoundary {
    data class Continues(val runId: String) : TimelineRunBoundary {
        init { require(runId.isNotBlank()) }
    }
    data object Ends : TimelineRunBoundary
    /** Not a split or a join. Consumers must retain partial runs until evidence is available. */
    data object Unknown : TimelineRunBoundary
}

/** Edges refer to the first/last physical resident record, including filtered records. */
data class TimelineRunEnvelope(val older: TimelineRunBoundary, val newer: TimelineRunBoundary)

/** Decoded evidence survives filtering so hidden rows cannot silently join unrelated runs. */
data class TimelineProjectionRecord(
    val record: TimelineSettledRecord,
    val event: TimelineEvent.Confirmed?,
    val excluded: Boolean,
    /** An undecoded body (too large to decode inline) whose head opens as a skill's instructions. */
    val skillEnvelopePreview: Boolean = false,
)

data class TimelinePageProjectionInput(
    val context: TimelineProjectionContext,
    val records: List<TimelineProjectionRecord>,
    val envelope: TimelineRunEnvelope,
) {
    /**
     * The page's canvas_compose receipts, attached over its presented events.
     * A receipt follows its TOOL_CALL record: a run split across pages puts it on the tool call's
     * page (on the call itself when the narration is on another page), so it is never shown twice.
     */
    internal val canvasReceipts: Map<String, List<CanvasArtifactReceipt>> by lazy {
        CanvasArtifactReceipts.attach(records.mapNotNull { it.event.takeUnless { _ -> it.excluded } })
    }
}

/** No final render rows are constructed until the complete bounded input has been prepared. */
internal fun TimelinePageProjectionInput.project(adapter: TimelineSettledProjectionAdapter): List<TimelineSettledRecord> =
    records.map { input ->
        val record = input.record
        val presentation = when {
            input.excluded -> TimelineSettledPresentation.Drop
            input.event == null -> TimelineSettledPresentation.Defer
            else -> adapter.project(record, input.event, context.ownAgentId)
                ?.withReceipts(canvasReceipts[CanvasArtifactReceipts.eventKey(input.event)])
                ?.let { TimelineSettledPresentation.Render(input.event, it) }
                ?: TimelineSettledPresentation.Drop
        }
        record.copy(preparedPresentation = presentation)
    }

// letta-mobile-a7ir6: one row per Monitor watch per page instead of one per event.
/**
 * [prepared] with each Monitor watch's events on this page folded
 * (foldMonitorEvents): the newest becomes the watch's chip in its own record's row, an older
 * actionable event keeps its row, an older routine one drops. A page reads no further than its
 * records, so a watch spanning a page boundary shows a chip on each page.
 */
internal fun foldMonitorEventsOnPage(prepared: List<TimelineSettledRecord>): List<TimelineSettledRecord> {
    val messages = prepared.map { (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item?.soleMessage() }
    val folded = foldMonitorEvents(messages)
    if (folded === messages) return prepared
    return prepared.mapIndexed { index, record ->
        val render = record.preparedPresentation as? TimelineSettledPresentation.Render
        val message = folded[index]
        when {
            render == null || message === messages[index] -> record
            message == null -> record.copy(preparedPresentation = TimelineSettledPresentation.Drop)
            else -> record.copy(preparedPresentation = render.copy(item = render.item.withSoleMessage(message)))
        }
    }
}

/** The one message of a per-record item; null for a page-built run of several. */
private fun ChatRenderItem.soleMessage(): UiMessage? = when (this) {
    is ChatRenderItem.Single -> message
    is ChatRenderItem.RunBlock -> messages.singleOrNull()?.first
}

private fun ChatRenderItem.withSoleMessage(message: UiMessage): ChatRenderItem = when (this) {
    is ChatRenderItem.Single -> copy(message = message)
    is ChatRenderItem.RunBlock -> copy(messages = messages.map { (_, position) -> message to position })
}

/** A per-record item (one message) carrying its page-attached receipts. */
private fun ChatRenderItem.withReceipts(receipts: List<CanvasArtifactReceipt>?): ChatRenderItem {
    if (receipts.isNullOrEmpty()) return this
    return when (this) {
        is ChatRenderItem.Single -> copy(message = message.copy(artifacts = receipts))
        is ChatRenderItem.RunBlock -> copy(messages = messages.map { (message, position) -> message.copy(artifacts = receipts) to position })
    }
}

/**
 * Groups renderable residents before Paging creates rows. Per-record projection is still retained
 * on every source record for deferred bodies and settlement provenance; only the first record in a
 * render block owns the combined item and the remaining records are dropped from presentation.
 *
 * A single-message run is claimed too: its page-built item carries the turn latency the run
 * disclosure needs, which a per-record projection cannot.
 *
 * A claimed block keys `run-<id>-from-<its oldest message id>`: a function of the block's own
 * records, never of where the page edges fell. A per-page `run-<id>` could be claimed by both pages
 * a run straddles, and a key chosen from the edges changes when a refresh around the reader moves
 * one. The live overlay keys its blocks the same way (CanonicalTimelinePresentation), so a settled
 * block that holds a whole live block takes that block's place under the same key.
 */
internal fun TimelinePageProjectionInput.aggregatePreparedRuns(
    prepared: List<TimelineSettledRecord>,
): List<TimelineSettledRecord> {
    val renderable = renderableRecords(prepared)
    if (renderable.size < 2) return prepared
    // The page's Monitor events are already folded (foldMonitorEventsOnPage).
    val grouped = buildFoldedChatRenderModel(
        messages = scopeMessagesToAgent(renderable.map { it.message }.withPromptOwnedRunIds(), context.scope.agentId),
        mode = ChatDisplayMode.Interactive,
    ).renderItems
    val output = prepared.toMutableList()
    grouped.mapNotNull { it.runClaim() }
        .filter { envelope.containsComplete(it.runId) }
        .map { it.keyedByOwnRecords() }
        .forEach { claim -> claimRun(output, claim, renderable) }
    return output
}

private fun TimelinePageProjectionInput.renderableRecords(
    prepared: List<TimelineSettledRecord>,
): List<IndexedRenderedRecord> = prepared.mapIndexedNotNull { index, record ->
    val presentation = record.preparedPresentation as? TimelineSettledPresentation.Render
        ?: return@mapIndexedNotNull null
    val message = timelineEventToUiMessage(
        presentation.event,
        context.ownAgentId,
        canvasReceipts[CanvasArtifactReceipts.eventKey(presentation.event)].orEmpty(),
    ) ?: return@mapIndexedNotNull null
    // A Monitor watch's chip (foldMonitorEventsOnPage) lives on the record's item, not on its event.
    val chip = presentation.item.soleMessage()?.subagentNotification?.takeIf { it.isMonitorWatcher() }
    IndexedRenderedRecord(index, record, presentation, chip?.let { message.copy(subagentNotification = it) } ?: message)
}

/** A page-built run and the message ids it owns, in chat order. */
private data class RunClaim(val item: ChatRenderItem.RunBlock, val runId: String, val memberIds: List<String>)

/** This block keyed by its oldest message, whatever page or snapshot it was cut from. */
private fun RunClaim.keyedByOwnRecords(): RunClaim = copy(item = item.keyedByOwnRecords())

private fun ChatRenderItem.runClaim(): RunClaim? = when (this) {
    is ChatRenderItem.RunBlock -> RunClaim(this, runId, messages.map { it.first.id })
        .takeIf { messages.size >= 2 }
    // Settled as the one-message block the per-record projection would have built, now carrying the
    // page-built message (its turn latency) under the run's key.
    is ChatRenderItem.Single -> stableRunId?.let { runId ->
        RunClaim(ChatRenderItem.RunBlock(runId, listOf(message to groupPosition), stableKey = key), runId, listOf(message.id))
    }
}

private fun TimelinePageProjectionInput.claimRun(
    output: MutableList<TimelineSettledRecord>,
    claim: RunClaim,
    renderable: List<IndexedRenderedRecord>,
) {
    val members = claim.memberIds.toSet()
    val sources = renderable.filter { it.message.id in members }
    if (sources.size != claim.memberIds.size || spansVisibleGap(sources)) return
    val owner = sources.minBy { it.index }
    output[owner.index] = owner.record.copy(
        preparedPresentation = TimelineSettledPresentation.Render(
            event = owner.presentation.event,
            item = claim.item,
            residentEvents = sources.map { it.residentEvent() },
        ),
    )
    sources.filter { it !== owner }.forEach { source ->
        output[source.index] = source.record.copy(preparedPresentation = TimelineSettledPresentation.Drop)
    }
}

/**
 * Skill instruction envelopes are hidden model context, not human turn boundaries. Returns are
 * folded into their canonical owners before projection; do not infer ownership for arbitrary
 * hidden rows (including orphan returns). A run's own stop_reason and usage frames close a step, not
 * the turn, and neither does a whitespace-only text segment between its tool
 * calls, which the settled projection drops.
 */
private fun TimelinePageProjectionInput.spansVisibleGap(sources: List<IndexedRenderedRecord>): Boolean =
    sources.map { it.index }.sorted().zipWithNext().any { (left, right) ->
        (left + 1 until right).any { index -> !isRunInterior(records[index]) }
    }

private fun TimelinePageProjectionInput.isRunInterior(record: TimelineProjectionRecord): Boolean =
    record.event?.isSyntheticSkillEnvelope() == true || record.skillEnvelopePreview || record.isRunMetadata() ||
        isContentlessSegment(record.event)

private fun TimelinePageProjectionInput.isContentlessSegment(event: TimelineEvent.Confirmed?): Boolean =
    event != null && timelineEventToUiMessage(event, context.ownAgentId)?.hasNoRenderableContent() == true

private fun IndexedRenderedRecord.residentEvent() = TimelineResidentEvent(
    identity = record.key.identity,
    revision = record.revision,
    otid = presentation.event.otid,
    serverId = presentation.event.serverId,
)

private fun TimelineRunEnvelope.containsComplete(runId: String): Boolean =
    older != TimelineRunBoundary.Continues(runId) && newer != TimelineRunBoundary.Continues(runId)

private data class IndexedRenderedRecord(
    val index: Int,
    val record: TimelineSettledRecord,
    val presentation: TimelineSettledPresentation.Render,
    val message: com.letta.mobile.data.model.UiMessage,
)

/** At most two metadata queries (one row each), two body reads, and 32 KiB additional bytes.
 * Body bytes also consume the remaining configured page budget; no scanning past an unknown edge.
 */
internal suspend fun TimelineStoreReader.runEnvelope(
    page: TimelineMetadataPage,
    records: List<TimelineProjectionRecord>,
    remainingBodyBytes: Long,
    adapter: TimelineSettledProjectionAdapter,
): TimelineRunEnvelope {
    var remaining = remainingBodyBytes
    suspend fun boundary(key: TimelinePageKey?, resident: TimelineProjectionRecord?, older: Boolean): TimelineRunBoundary {
        if (key == null) return TimelineRunBoundary.Ends
        if (resident?.event == null) return TimelineRunBoundary.Unknown
        val position = if (older) TimelineReadPosition.Before(resident.record.key)
            else TimelineReadPosition.After(resident.record.key)
        val adjacent = metadata(position, 1)
        check(adjacent.rows.size <= 1 && adjacent.revision == page.revision) { "Invalid boundary snapshot" }
        val row = adjacent.rows.singleOrNull() ?: return TimelineRunBoundary.Unknown
        check(if (older) row.key < resident.record.key else row.key > resident.record.key) { "Invalid boundary key" }
        if (row.contentType != TIMELINE_EVENT_CONTENT_TYPE ||
            row.body.encodedBytes > minOf(remaining, MAX_BOUNDARY_BODY_BYTES)
        ) return TimelineRunBoundary.Unknown
        val size = row.body.encodedBytes.toInt()
        val bytes = if (size == 0) byteArrayOf() else body(row.body, 0, size)
        check(bytes.size == size) { "Incomplete boundary body" }
        remaining -= size
        // Never catch decode failures and reinterpret corrupt canonical evidence as Unknown.
        val event = adapter.decode(TimelineSettledRecord(row.key, row.contentType, bytes, page.revision, row.body))
        val residentEvent = resident.event
        val run = residentEvent.runId?.takeIf { it.isNotBlank() }
        return if (run != null && run == event.runId) TimelineRunBoundary.Continues(run)
        else TimelineRunBoundary.Ends
    }
    return TimelineRunEnvelope(
        boundary(page.older, records.firstOrNull(), older = true),
        boundary(page.newer, records.lastOrNull(), older = false),
    )
}

internal const val MAX_BOUNDARY_BODY_BYTES: Long = 16L * 1024

/**
 * [record] with its decoded [event] (null when undecoded), and whether the page hides it: an opaque
 * body, a skill's instructions (decoded, or recognised by the head of a body too large to decode), or
 * an event [isSuppressed] says is drawn elsewhere.
 */
internal suspend fun TimelineStoreReader.projectionRecord(
    record: TimelineSettledRecord,
    event: TimelineEvent.Confirmed?,
    isSuppressed: suspend (TimelineEvent.Confirmed) -> Boolean,
): TimelineProjectionRecord {
    if (record.contentType != TIMELINE_EVENT_CONTENT_TYPE) return TimelineProjectionRecord(record, event, excluded = true)
    if (event == null) {
        val skillEnvelope = isSkillEnvelopePreview(record)
        return TimelineProjectionRecord(record, null, excluded = skillEnvelope, skillEnvelopePreview = skillEnvelope)
    }
    return TimelineProjectionRecord(record, event, excluded = event.isSyntheticSkillEnvelope() || isSuppressed(event))
}

/** Whether an undecoded event body is a skill's instructions: still hidden model context, which only its head can say. */
private suspend fun TimelineStoreReader.isSkillEnvelopePreview(record: TimelineSettledRecord): Boolean {
    val pointer = record.pointer ?: return false
    return opensSkillEnvelope(body(pointer, 0, minOf(pointer.encodedBytes, SKILL_ENVELOPE_HEAD_BYTES.toLong()).toInt()))
}

/**
 * Hands each skill's instructions to the call that loaded them. The runtime sends a skill's
 * instructions as their own hidden message just after its `Skill` call, and the page drops that
 * message (model context, not a turn); the nearest earlier skill call on the page that holds no
 * instructions yet takes them: their text when the message was decoded, or a pointer to its stored
 * body when it was too large to decode, which the call's card reads a page at a time. Runs after
 * [aggregatePreparedRuns], whose output stays aligned with [TimelinePageProjectionInput.records].
 */
internal fun TimelinePageProjectionInput.attachSkillInstructions(
    prepared: List<TimelineSettledRecord>,
): List<TimelineSettledRecord> {
    val output = prepared.toMutableList()
    val claimed = mutableListOf<UiToolCall>()
    records.forEachIndexed { index, source ->
        val text = source.event?.takeIf { it.isSyntheticSkillEnvelope() }?.content
        val stored = source.record.takeIf { source.skillEnvelopePreview }?.bodyReference(context.scope)
        if (text == null && stored == null) return@forEachIndexed
        val owner = (index - 1 downTo 0).firstNotNullOfOrNull { at ->
            val render = output[at].preparedPresentation as? TimelineSettledPresentation.Render
            render?.item?.unclaimedSkillCall(claimed)?.let { call -> Triple(at, render, call) }
        } ?: return@forEachIndexed
        val (at, render, call) = owner
        claimed += call
        output[at] = output[at].copy(preparedPresentation = render.withSkillInstructions(call, text, stored))
    }
    return output
}

private fun TimelineSettledRecord.bodyReference(scope: TimelineScope): TimelineBodyReference? =
    pointer?.let { TimelineBodyReference(scope, key, it, contentType, revision) }

/** The newest skill call in this item that has an id, no instructions yet, and is not already [claimed] on this page. */
private fun ChatRenderItem.unclaimedSkillCall(claimed: List<UiToolCall>): UiToolCall? {
    val messages = when (this) {
        is ChatRenderItem.Single -> listOf(message)
        is ChatRenderItem.RunBlock -> messages.map { it.first }
    }
    return messages.asReversed().asSequence().flatMap { it.toolCalls.orEmpty().asReversed() }.firstOrNull { call ->
        call.loadsSkill() && call.skillInstructions == null && !call.toolCallId.isNullOrBlank() &&
            claimed.none { it.toolCallId == call.toolCallId }
    }
}

private fun UiToolCall.loadsSkill(): Boolean = SkillToolPresenter.matcher.names.any { it.equals(name, ignoreCase = true) }

/** This item with [owner] (a skill call it holds, with an id) holding the instructions: their [text], or the [stored] body's pointer. */
private fun TimelineSettledPresentation.Render.withSkillInstructions(
    owner: UiToolCall,
    text: String?,
    stored: TimelineBodyReference?,
): TimelineSettledPresentation.Render {
    val key = requireNotNull(owner.toolCallId)
    return copy(
        item = if (text == null) item else item.mapToolCalls { call ->
            if (call.toolCallId == key) call.copy(skillInstructions = text) else call
        },
        skillInstructionBodies = stored?.let { skillInstructionBodies + (key to it) } ?: skillInstructionBodies,
    )
}

private fun ChatRenderItem.mapToolCalls(transform: (UiToolCall) -> UiToolCall): ChatRenderItem = when (this) {
    is ChatRenderItem.Single -> copy(message = message.copy(toolCalls = message.toolCalls?.map(transform)))
    is ChatRenderItem.RunBlock -> copy(
        messages = messages.map { (message, position) -> message.copy(toolCalls = message.toolCalls?.map(transform)) to position },
    )
}

/**
 * Whether an undecoded stored event body opens as a skill's instructions, from its first bytes:
 * its `content` string is serialized right after its position and otid, so the head carries the
 * envelope's opening tag and frontmatter. Read leniently, as the head ends mid-body.
 */
internal fun opensSkillEnvelope(head: ByteArray): Boolean {
    val text = head.decodeToString()
    val start = text.indexOf(CONTENT_FIELD)
    if (start < 0) return false
    return SyntheticSkillEnvelopeDetector.opensSkillEnvelope(jsonStringPrefix(text, start + CONTENT_FIELD.length))
}

/** The decoded opening of the JSON string starting at [from], up to its end or the first escape cut off by the head's end. */
private fun jsonStringPrefix(text: String, from: Int): String = buildString {
    var at = from
    while (at < text.length) {
        val char = text[at]
        when {
            char == '"' -> return@buildString
            char != '\\' -> append(char).also { at++ }
            at + 1 >= text.length -> return@buildString
            text[at + 1] == 'u' -> {
                if (at + UNICODE_ESCAPE > text.length) return@buildString
                val code = text.substring(at + 2, at + UNICODE_ESCAPE).toIntOrNull(HEX) ?: return@buildString
                append(code.toChar())
                at += UNICODE_ESCAPE
            }
            else -> {
                append(JSON_ESCAPES[text[at + 1]] ?: text[at + 1])
                at += 2
            }
        }
    }
}

/** How much of an undecoded body is read to tell whether it is a skill's instructions. */
internal const val SKILL_ENVELOPE_HEAD_BYTES = 2 * 1024
private const val CONTENT_FIELD = "\"content\":\""
private const val HEX = 16
private const val UNICODE_ESCAPE = 6
private val JSON_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')
