package com.letta.mobile.data.timeline.headless

import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.WsFrameMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HeadlessTimelineReplayer(
    private val store: HeadlessTimelineStore = HeadlessTimelineStore(),
    private val json: Json = replayJson,
) {
    suspend fun replayJsonl(
        conversationId: String,
        lines: Sequence<String>,
        assertNoDuplicateUiMessages: Boolean = false,
        assertOtidUnique: Boolean = false,
        assertSeqMonotonic: Boolean = false,
        assertNoEmptyBodies: Boolean = false,
        assertNoPrefixOrphans: Boolean = false,
        expectedUiMessageCountPerRun: Int? = null,
        expectedFinalStatus: String? = null,
        assertNoOrphanToolReturns: Boolean = false,
        assertRunCompletes: Boolean = false,
        assertNoAbandonedToolCalls: Boolean = false,
        assertApprovalToolReturnOnApprovalRun: Boolean = false,
        assertOtidStableAcrossRetry: Boolean = false,
        dumpOptions: HeadlessReplayDumpOptions = HeadlessReplayDumpOptions(),
    ): HeadlessReplayResult {
        val assertionOptions = TimelineAssertionOptions(
            assertNoDuplicateUiMessages = assertNoDuplicateUiMessages,
            assertOtidUnique = assertOtidUnique,
            assertSeqMonotonic = assertSeqMonotonic,
            assertNoEmptyBodies = assertNoEmptyBodies,
            assertNoPrefixOrphans = assertNoPrefixOrphans,
            expectedUiMessageCountPerRun = expectedUiMessageCountPerRun,
            expectedFinalStatus = expectedFinalStatus,
            assertNoOrphanToolReturns = assertNoOrphanToolReturns,
            assertRunCompletes = assertRunCompletes,
            assertNoAbandonedToolCalls = assertNoAbandonedToolCalls,
            assertApprovalToolReturnOnApprovalRun = assertApprovalToolReturnOnApprovalRun,
            assertOtidStableAcrossRetry = assertOtidStableAcrossRetry,
        )
        return replayJsonl(
            conversationId = conversationId,
            lines = lines,
            assertionOptions = assertionOptions,
            dumpOptions = dumpOptions,
        )
    }

    suspend fun replayJsonl(
        conversationId: String,
        lines: Sequence<String>,
        assertionOptions: TimelineAssertionOptions,
        dumpOptions: HeadlessReplayDumpOptions = HeadlessReplayDumpOptions(),
    ): HeadlessReplayResult {
        val session = HeadlessTimelineReplaySession(
            conversationId = conversationId,
            store = store,
            json = json,
        )
        val snapshots = mutableListOf<HeadlessReplayFrameSnapshot>()
        lines.forEach { line ->
            val nextIndex = session.framesSeen
            val step = session.ingestLine(
                line = line,
                captureTimeline = dumpOptions.shouldCapture(nextIndex),
            ) ?: return@forEach
            step.snapshot?.let { snapshots += it }
        }
        return session.result(
            assertionOptions = assertionOptions,
            frameSnapshots = snapshots,
        )
    }
}

class HeadlessTimelineReplaySession(
    private val conversationId: String,
    private val store: HeadlessTimelineStore = HeadlessTimelineStore(),
    private val json: Json = replayJson,
) {
    var framesSeen: Int = 0
        private set
    var messagesIngested: Int = 0
        private set

    private val ignoredTypes = linkedMapOf<String, Int>()
    private val seqsByRun = linkedMapOf<String, MutableList<Long>>()
    private val finalStatusesByRun = linkedMapOf<String, String>()
    private val observedRunIds = linkedSetOf<String>()
    private val toolCallIdsByRun = linkedMapOf<String?, MutableSet<String>>()
    private val toolReturns = mutableListOf<ObservedToolReturn>()
    private val approvalRunByToolCallId = linkedMapOf<String, String>()
    private val otidsByMessageKey = linkedMapOf<String, MutableSet<String>>()

    suspend fun ingestLine(
        line: String,
        captureTimeline: Boolean = false,
    ): HeadlessReplayStep? {
        val rawLine = line.trim()
        if (rawLine.isEmpty()) return null
        val frameIndex = framesSeen++
        val frameJson = rawLine.toRecordedFrameJsonOrNull() ?: run {
            ignoredTypes.increment("<invalid>")
            return step(
                frameIndex = frameIndex,
                frameType = "<invalid>",
                frameId = null,
                ingested = false,
                ignoredReason = "invalid json",
                captureTimeline = captureTimeline,
            )
        }

        seqsByRun.recordSeq(frameJson)
        val frame = runCatching {
            json.decodeFromString(ServerFrameSerializer, frameJson.toString())
        }.getOrElse {
            val frameType = frameJson.typeName()
            ignoredTypes.increment(frameType)
            return step(
                frameIndex = frameIndex,
                frameType = frameType,
                frameId = frameJson.frameId(),
                ingested = false,
                ignoredReason = "decode-error",
                captureTimeline = captureTimeline,
            )
        }

        val innerPair = if (frame is ServerFrame.SubscribeFrameMessage) {
            runCatching {
                json.decodeFromString(ServerFrameSerializer, frame.frame.toString()) to frame.frame
            }.getOrNull()
        } else {
            frame to frameJson
        }
        if (innerPair == null) {
            ignoredTypes.increment("subscribe_frame")
            return step(
                frameIndex = frameIndex,
                frameType = frameJson.typeName(),
                frameId = frameJson.frameId(),
                ingested = false,
                ignoredReason = "decode-error",
                captureTimeline = captureTimeline,
            )
        }

        val (innerFrame, innerJson) = innerPair
        if (frame is ServerFrame.SubscribeFrameMessage) seqsByRun.recordSeq(innerJson)
        recordObservedFrame(innerFrame, innerJson)
        val message = WsFrameMapper.toLettaMessage(innerFrame)
        val frameType = innerJson.typeName()
        val frameId = innerJson.frameId()
        if (message == null) {
            ignoredTypes.increment(frameType)
            return step(
                frameIndex = frameIndex,
                frameType = frameType,
                frameId = frameId,
                ingested = false,
                ignoredReason = "no timeline message",
                captureTimeline = captureTimeline,
            )
        }

        val targetConversation = innerFrame.conversationIdOrNull() ?: conversationId
        store.ingestExternalTransportMessage(targetConversation, message)
        messagesIngested++
        return step(
            frameIndex = frameIndex,
            frameType = frameType,
            frameId = frameId,
            ingested = true,
            ignoredReason = null,
            captureTimeline = captureTimeline,
        )
    }

    suspend fun dumpJson(): String = store.dumpJson(conversationId)

    suspend fun assertTimeline(options: TimelineAssertionOptions): TimelineAssertionReport {
        val timelineReport = store.assertTimeline(
            conversationId = conversationId,
            options = options,
        )
        val timeline = store.snapshot(conversationId)
        val failures = timelineReport.failures.toMutableList()
        if (options.assertSeqMonotonic) {
            failures += rawSeqFailures()
        }
        options.expectedFinalStatus?.let { expected ->
            val actual = finalStatusesByRun.entries.lastOrNull()?.value
            if (actual != expected) {
                failures += "final run status ${actual ?: "<none>"} does not match expected $expected"
            }
        }
        if (options.assertNoOrphanToolReturns) {
            failures += orphanToolReturnFailures()
        }
        if (options.assertRunCompletes) {
            failures += runCompletionFailures(timeline)
        }
        if (options.assertNoAbandonedToolCalls) {
            failures += abandonedToolCallFailures(timeline)
        }
        if (options.assertApprovalToolReturnOnApprovalRun) {
            failures += approvalToolReturnRunFailures(timeline)
        }
        if (options.assertOtidStableAcrossRetry) {
            failures += otidStabilityFailures()
        }
        return timelineReport.copy(failures = failures)
    }

    suspend fun result(
        assertionOptions: TimelineAssertionOptions,
        frameSnapshots: List<HeadlessReplayFrameSnapshot> = emptyList(),
    ): HeadlessReplayResult {
        val report = assertTimeline(assertionOptions)
        return HeadlessReplayResult(
            conversationId = conversationId,
            framesSeen = framesSeen,
            messagesIngested = messagesIngested,
            ignoredFrameTypes = ignoredTypes.toMap(),
            assertionReport = report,
            timelineJson = store.dumpJson(conversationId),
            frameSnapshots = frameSnapshots,
        )
    }

    private suspend fun step(
        frameIndex: Int,
        frameType: String,
        frameId: String?,
        ingested: Boolean,
        ignoredReason: String?,
        captureTimeline: Boolean,
    ): HeadlessReplayStep {
        val snapshot = if (captureTimeline) {
            HeadlessReplayFrameSnapshot(
                frameIndex = frameIndex,
                frameType = frameType,
                frameId = frameId,
                ingested = ingested,
                ignoredReason = ignoredReason,
                timeline = store.dumpObject(conversationId),
            )
        } else {
            null
        }
        return HeadlessReplayStep(
            frameIndex = frameIndex,
            frameType = frameType,
            frameId = frameId,
            ingested = ingested,
            ignoredReason = ignoredReason,
            snapshot = snapshot,
        )
    }

    private fun recordObservedFrame(frame: ServerFrame, frameJson: JsonObject) {
        frame.runIdForAssertionOrNull()?.let { observedRunIds += it }
        recordMessageOtid(frameJson)
        when (frame) {
            is ServerFrame.TurnDone -> finalStatusesByRun[frame.runId] = frame.status
            is ServerFrame.SubscribeDone -> finalStatusesByRun[frame.runId] = frame.status
            is ServerFrame.Error -> frame.runId?.let { finalStatusesByRun[it] = "failed" }
            is ServerFrame.ToolCallMessage -> {
                val runKey = frame.runId
                frame.toolCallIds().forEach { id ->
                    toolCallIdsByRun.getOrPut(runKey) { linkedSetOf() } += id
                    if (frame.type == "approval_request_message") {
                        approvalRunByToolCallId[id] = frame.runId
                    }
                }
            }
            is ServerFrame.ToolReturnMessage -> {
                toolReturns += ObservedToolReturn(
                    runId = frame.runId,
                    frameId = frame.id,
                    toolCallId = frame.toolCallId,
                )
            }
            else -> Unit
        }
    }

    private fun rawSeqFailures(): List<String> = seqsByRun.flatMap { (runId, seqs) ->
        if (seqs.zipWithNext().any { (a, b) -> b < a }) {
            listOf("non-monotonic recorded seq for run $runId: ${seqs.joinToString()}")
        } else {
            emptyList()
        }
    }

    private fun orphanToolReturnFailures(): List<String> = toolReturns.mapNotNull { toolReturn ->
        val matchingCalls = toolCallIdsByRun[toolReturn.runId].orEmpty()
        if (toolReturn.toolCallId in matchingCalls) {
            null
        } else {
            "orphan tool_return ${toolReturn.frameId} in run ${toolReturn.runId}: tool_call_id=${toolReturn.toolCallId}"
        }
    }

    private fun runCompletionFailures(timeline: Timeline): List<String> {
        val timelineRunIds = timeline.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .mapNotNull { it.runId }
        val runIds = (observedRunIds + timelineRunIds).sorted()
        return runIds.mapNotNull { runId ->
            val status = finalStatusesByRun[runId]
            if (status in TERMINAL_RUN_STATUSES) {
                null
            } else {
                "run $runId did not reach terminal status (last status=${status ?: "<none>"})"
            }
        }
    }

    private fun abandonedToolCallFailures(timeline: Timeline): List<String> {
        val terminalRunIds = finalStatusesByRun
            .filterValues { it in TERMINAL_RUN_STATUSES }
            .keys
            .toSet()
        return timeline.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.TOOL_CALL && it.runId in terminalRunIds }
            .flatMap { event ->
                event.toolCalls.mapNotNull { toolCall ->
                    val callId = toolCall.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (callId in event.toolReturnContentByCallId) {
                        null
                    } else {
                        "abandoned tool_call $callId in run ${event.runId}: ${event.serverId} reached " +
                            "${finalStatusesByRun[event.runId]}"
                    }
                }
            }
    }

    private fun approvalToolReturnRunFailures(timeline: Timeline): List<String> {
        val approvalRunByCallId = timeline.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.approvalRequestId != null && it.runId != null }
            .flatMap { event ->
                event.toolCalls.mapNotNull { toolCall ->
                    val callId = toolCall.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    callId to event.runId.orEmpty()
                }
            }
            .toMap()
            .let { fromTimeline -> approvalRunByToolCallId + fromTimeline }
        return toolReturns.mapNotNull { toolReturn ->
            val approvalRunId = approvalRunByCallId[toolReturn.toolCallId] ?: return@mapNotNull null
            if (toolReturn.runId == approvalRunId) {
                null
            } else {
                "approval tool_return ${toolReturn.frameId} for tool_call_id=${toolReturn.toolCallId} " +
                    "landed on run ${toolReturn.runId ?: "<none>"} instead of approval run $approvalRunId"
            }
        }
    }

    private fun otidStabilityFailures(): List<String> =
        otidsByMessageKey.mapNotNull { (messageKey, otids) ->
            if (otids.size <= 1) {
                null
            } else {
                "message $messageKey observed with multiple otids: ${otids.sorted().joinToString()}"
            }
        }

    private fun recordMessageOtid(frameJson: JsonObject) {
        val otid = frameJson["otid"]?.jsonPrimitive?.contentOrNull ?: return
        val type = frameJson.typeName()
        val id = frameJson.frameId() ?: return
        otidsByMessageKey.getOrPut("$type/$id") { linkedSetOf() } += otid
    }
}

data class HeadlessReplayDumpOptions(
    val dumpAfterEachFrame: Boolean = false,
    val dumpAfterFrame: Int? = null,
    val dumpFrames: Set<Int> = emptySet(),
) {
    fun shouldCapture(frameIndex: Int): Boolean =
        dumpAfterEachFrame || dumpAfterFrame == frameIndex || frameIndex in dumpFrames

    val enabled: Boolean get() = dumpAfterEachFrame || dumpAfterFrame != null || dumpFrames.isNotEmpty()
}

data class HeadlessReplayStep(
    val frameIndex: Int,
    val frameType: String,
    val frameId: String?,
    val ingested: Boolean,
    val ignoredReason: String?,
    val snapshot: HeadlessReplayFrameSnapshot?,
)

data class HeadlessReplayFrameSnapshot(
    val frameIndex: Int,
    val frameType: String,
    val frameId: String?,
    val ingested: Boolean,
    val ignoredReason: String?,
    val timeline: JsonObject,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("frame_index", frameIndex)
        put("frame_type", frameType)
        put("frame_id", frameId)
        put("ingested", ingested)
        put("ignored_reason", ignoredReason)
        put("timeline", timeline)
    }
}

data class HeadlessReplayResult(
    val conversationId: String,
    val framesSeen: Int,
    val messagesIngested: Int,
    val ignoredFrameTypes: Map<String, Int>,
    val assertionReport: TimelineAssertionReport,
    val timelineJson: String,
    val frameSnapshots: List<HeadlessReplayFrameSnapshot> = emptyList(),
) {
    fun frameSnapshotsJson(pretty: Boolean = true): String {
        val array = buildJsonArray {
            frameSnapshots.forEach { add(it.toJsonObject()) }
        }
        val encoder = if (pretty) prettyJson else compactJson
        return encoder.encodeToString(JsonArray.serializer(), array)
    }
}

private data class ObservedToolReturn(
    val runId: String?,
    val frameId: String,
    val toolCallId: String,
)

private val TERMINAL_RUN_STATUSES = setOf("completed", "cancelled", "failed")

private fun String.toRecordedFrameJsonOrNull(): JsonObject? {
    val element = runCatching { replayJson.parseToJsonElement(this).jsonObject }.getOrNull() ?: return null
    val raw = element["raw"]?.jsonPrimitive?.contentOrNull
    if (raw != null) {
        return runCatching { replayJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
    val frame = element["frame"]
    if (frame is JsonObject) return frame
    return element.takeIf { it["type"] != null }
}

private fun MutableMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun MutableMap<String, MutableList<Long>>.recordSeq(frame: JsonObject) {
    val runId = frame["run_id"]?.jsonPrimitive?.contentOrNull ?: return
    val seq = frame["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: frame["seq_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: return
    getOrPut(runId) { mutableListOf() } += seq
}

private fun JsonObject.typeName(): String =
    this["type"]?.jsonPrimitive?.contentOrNull ?: "<unknown>"

private fun JsonObject.frameId(): String? =
    this["id"]?.jsonPrimitive?.contentOrNull

private fun ServerFrame.conversationIdOrNull(): String? = when (this) {
    is ServerFrame.TurnStarted -> conversationId
    is ServerFrame.AssistantMessage -> conversationId
    is ServerFrame.ReasoningMessage -> conversationId
    is ServerFrame.ToolCallMessage -> conversationId
    is ServerFrame.ToolReturnMessage -> conversationId
    is ServerFrame.A2ui -> conversationId
    else -> null
}

private fun ServerFrame.runIdForAssertionOrNull(): String? = when (this) {
    is ServerFrame.TurnStarted -> runId
    is ServerFrame.TurnDone -> runId
    is ServerFrame.SubscribeDone -> runId
    is ServerFrame.AssistantMessage -> runId
    is ServerFrame.ReasoningMessage -> runId
    is ServerFrame.ToolCallMessage -> runId
    is ServerFrame.ToolReturnMessage -> runId
    is ServerFrame.StopReason -> runId
    is ServerFrame.UsageStatistics -> runId
    is ServerFrame.Error -> runId
    is ServerFrame.A2ui -> runId
    else -> null
}

private fun ServerFrame.ToolCallMessage.toolCallIds(): List<String> {
    val payloads = when {
        toolCalls != null -> toolCalls.orEmpty()
        toolCall != null -> listOfNotNull(toolCall)
        else -> emptyList()
    }
    return payloads.map { it.toolCallId }.filter { it.isNotBlank() }
}

private val replayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

private val prettyJson = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}

private val compactJson = Json {
    explicitNulls = false
    encodeDefaults = true
}
