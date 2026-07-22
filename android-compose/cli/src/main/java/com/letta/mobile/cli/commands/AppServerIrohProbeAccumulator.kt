package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal class ProbeAccumulator(private val turn: Int, private val dumpPath: ProbeDumpPath? = null) {
    private val assistantIds = linkedSetOf<String>()
    private val assistantFinalTextLengths = linkedMapOf<String, Int>()
    private val reasoningIds = linkedSetOf<String>()
    private val eventSeqs = mutableListOf<Long>()
    private val openToolCallIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    val scenarioViolations = mutableListOf<String>()
    var assistantDeltaCount = 0
        private set

    @Volatile
    var terminalCount = 0
        private set

    @Volatile
    var recordedFrameCount = 0
        private set
    var untypedFrameCount = 0
        private set
    var framesAfterTerminal = 0
        private set
    val observedEventSeqs: List<Long>
        get() = eventSeqs.toList()
    var terminalStatus: String? = null
        private set
    var terminalRunId: String? = null
        private set

    @Volatile
    var activeRunId: String? = null
        private set

    fun record(frame: AppServerInboundFrame) {
        maybeDump(frame)
        recordedFrameCount += 1
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> recordStreamDelta(frame)
            is AppServerInboundFrame.AbortMessageResponse ->
                if (!frame.success) errors += frame.error ?: "abort_message failed"
            is AppServerInboundFrame.RuntimeStartResponse ->
                if (!frame.success) errors += frame.error ?: "runtime_start failed"
            is AppServerInboundFrame.AuthResponse ->
                if (!frame.success) errors += frame.error ?: "auth failed"
            is AppServerInboundFrame.Unknown ->
                errors += "unknown:${frame.type ?: "missing_type"}"
            else -> Unit
        }
    }

    fun toMetrics(request: ProbeMetricsRequest): IrohProbeTurnMetrics = IrohProbeTurnMetrics(
        turn = turn,
        dialMs = request.dialMs,
        firstFrameMs = request.firstFrameMs,
        assistantDeltaCount = assistantDeltaCount,
        assistantMessageIds = assistantIds.toList(),
        reasoningMessageIds = reasoningIds.toList(),
        reasoningRowEstimate = reasoningIds.size,
        turnDoneCount = terminalCount,
        errorFrames = errors,
        dialSucceeded = request.dialMs != null,
        timedOut = request.timedOut,
        assistantFinalTextLengths = assistantFinalTextLengths.values.toList(),
        scenarioViolations = scenarioViolations + request.extraViolations,
        notes = request.extraNotes,
        scenario = request.scenario?.value,
        profile = request.profile,
        eventSeqs = eventSeqs.toList(),
        untypedFrameCount = untypedFrameCount,
        framesAfterTerminal = framesAfterTerminal,
        terminalStatus = terminalStatus,
        terminalRunId = terminalRunId,
        activeRunId = activeRunId,
        openToolCallIds = openToolCallIds.toList(),
    )

    private fun maybeDump(frame: AppServerInboundFrame) {
        val path = dumpPath ?: return
        if (frame is AppServerInboundFrame.StreamDelta) {
            runCatching { File(path.value).appendText(frame.delta.toString() + "\n") }
        }
    }

    private fun recordStreamDelta(frame: AppServerInboundFrame.StreamDelta) {
        eventSeqs += frame.eventSeq
        val delta = runCatching { frame.delta.jsonObject }.getOrNull()
        if (delta == null) {
            untypedFrameCount += 1
        } else {
            recordDelta(delta, ProbeFallbackId(frame.idempotencyKey))
        }
    }

    private fun recordDelta(delta: JsonObject, fallbackId: ProbeFallbackId) {
        val terminalAlreadySeen = terminalCount > 0
        noteUntypedMessageType(delta)
        captureActiveRunId(delta)
        dispatchMessageType(delta, fallbackId)
        if (terminalAlreadySeen) framesAfterTerminal += 1
    }

    private fun noteUntypedMessageType(delta: JsonObject) {
        val messageType = delta.string("message_type")
        if (messageType == null || messageType !in IrohProbeAssertions.TYPED_MESSAGE_TYPES) {
            untypedFrameCount += 1
        }
    }

    private fun captureActiveRunId(delta: JsonObject) {
        delta.string("run_id")?.let { runId -> if (activeRunId == null) activeRunId = runId }
    }

    private fun dispatchMessageType(delta: JsonObject, fallbackId: ProbeFallbackId) {
        when (delta.string("message_type")) {
            "assistant_message" -> recordAssistant(delta, fallbackId)
            "reasoning_message", "hidden_reasoning_message" ->
                reasoningIds += delta.string("id") ?: fallbackId.value
            "tool_call_message" -> toolCallId(delta)?.let { openToolCallIds += it }
            "tool_return_message" -> toolCallId(delta)?.let { openToolCallIds -= it }
            "stop_reason" -> recordStopReason(delta)
            "loop_error", "error_message" -> recordErrorTerminal(delta)
        }
    }

    private fun recordAssistant(delta: JsonObject, fallbackId: ProbeFallbackId) {
        val id = delta.string("id") ?: fallbackId.value
        assistantDeltaCount += 1
        assistantIds += id
        val text = delta.textContent("text")
            ?: delta.textContent("content")
            ?: delta.textContent("message")
        if (text != null) assistantFinalTextLengths[id] = text.length
    }

    private fun recordStopReason(delta: JsonObject) {
        terminalCount += 1
        terminalRunId = delta.string("run_id")
        terminalStatus = delta.string("status")
            ?: if (delta.string("stop_reason") == "cancelled") "cancelled" else "completed"
    }

    private fun recordErrorTerminal(delta: JsonObject) {
        terminalCount += 1
        terminalRunId = delta.string("run_id")
        terminalStatus = delta.string("status") ?: "failed"
        errors += delta.string("message") ?: delta.string("error") ?: delta.toString()
    }

    private fun toolCallId(delta: JsonObject): String? =
        delta.string("tool_call_id")
            ?: (delta["tool_call"] as? JsonObject)?.string("tool_call_id")
            ?: (delta["tool_return"] as? JsonObject)?.string("tool_call_id")
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.probeTextContent(key: String): String? = this[key]?.extractProbeTextContent()
private fun JsonObject.textContent(key: String): String? = probeTextContent(key)
private fun JsonElement.extractProbeTextContent(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("") { it.extractProbeTextContent() }
    is JsonObject -> listOf("text", "content", "value")
        .firstNotNullOfOrNull { field -> this[field]?.extractProbeTextContent() }
        .orEmpty()
}
