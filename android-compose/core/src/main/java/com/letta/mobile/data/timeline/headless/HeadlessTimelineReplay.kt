package com.letta.mobile.data.timeline.headless

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.WsFrameMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    ): HeadlessReplayResult {
        var seen = 0
        var ingested = 0
        val ignoredTypes = linkedMapOf<String, Int>()
        val seqsByRun = linkedMapOf<String, MutableList<Long>>()
        lines.forEach { line ->
            val rawLine = line.trim()
            if (rawLine.isEmpty()) return@forEach
            seen++
            val frameJson = rawLine.toRecordedFrameJsonOrNull() ?: run {
                ignoredTypes.increment("<invalid>")
                return@forEach
            }
            if (assertSeqMonotonic) seqsByRun.recordSeq(frameJson)
            val frame = runCatching {
                json.decodeFromString(ServerFrameSerializer, frameJson.toString())
            }.getOrElse {
                ignoredTypes.increment(frameJson["type"]?.jsonPrimitive?.contentOrNull ?: "<decode-error>")
                return@forEach
            }
            val inner = if (frame is ServerFrame.SubscribeFrameMessage) {
                runCatching {
                    if (assertSeqMonotonic) seqsByRun.recordSeq(frame.frame)
                    json.decodeFromString(ServerFrameSerializer, frame.frame.toString())
                }.getOrNull()
            } else {
                frame
            }
            if (inner == null) {
                ignoredTypes.increment("subscribe_frame")
                return@forEach
            }
            val message = WsFrameMapper.toLettaMessage(inner)
            if (message == null) {
                ignoredTypes.increment(frameJson["type"]?.jsonPrimitive?.contentOrNull ?: inner::class.simpleName.orEmpty())
                return@forEach
            }
            val targetConversation = inner.conversationIdOrNull() ?: conversationId
            store.ingestExternalTransportMessage(targetConversation, message)
            ingested++
        }
        val timelineReport = store.assertTimeline(
            conversationId = conversationId,
            assertNoDuplicateUiMessages = assertNoDuplicateUiMessages,
            assertOtidUnique = assertOtidUnique,
            assertSeqMonotonic = assertSeqMonotonic,
        )
        val rawSeqFailures = if (assertSeqMonotonic) {
            seqsByRun.flatMap { (runId, seqs) ->
                if (seqs.zipWithNext().any { (a, b) -> b < a }) {
                    listOf("non-monotonic recorded seq for run $runId: ${seqs.joinToString()}")
                } else {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
        val report = timelineReport.copy(failures = timelineReport.failures + rawSeqFailures)
        return HeadlessReplayResult(
            conversationId = conversationId,
            framesSeen = seen,
            messagesIngested = ingested,
            ignoredFrameTypes = ignoredTypes.toMap(),
            assertionReport = report,
            timelineJson = store.dumpJson(conversationId),
        )
    }
}

data class HeadlessReplayResult(
    val conversationId: String,
    val framesSeen: Int,
    val messagesIngested: Int,
    val ignoredFrameTypes: Map<String, Int>,
    val assertionReport: TimelineAssertionReport,
    val timelineJson: String,
)

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

private fun ServerFrame.conversationIdOrNull(): String? = when (this) {
    is ServerFrame.TurnStarted -> conversationId
    is ServerFrame.AssistantMessage -> conversationId
    is ServerFrame.ReasoningMessage -> conversationId
    is ServerFrame.ToolCallMessage -> conversationId
    is ServerFrame.ToolReturnMessage -> conversationId
    is ServerFrame.A2ui -> conversationId
    else -> null
}

private val replayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
