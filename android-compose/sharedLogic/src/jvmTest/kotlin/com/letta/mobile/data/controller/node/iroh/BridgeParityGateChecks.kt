package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * letta-mobile-qygvv.14: the bridge parity gate as named checks. Each check reads one fixture's
 * DirectRun and BridgeRun and returns what diverged, or null. A fixture may waive a check only by
 * naming the bead that tracks the divergence (ParityFixture.divergences); the known-divergence test
 * fails once the divergence is gone, so a waiver cannot outlive its bug.
 */

/** One fixture replayed both ways. */
internal class ParityRuns(
    val fixture: ParityFixture,
    val recording: AppServerRecording,
    val direct: DirectRun,
    val bridge: BridgeRun,
) {
    val phone: List<WireFrame> get() = bridge.phone.stream
    val recordedFinished: JsonObject = recording.parsedFrames.last { it.parityString("type") == "turn_finished" }

    /** Runs the App Server bound to this turn's input (its loop status `client_message_ids_by_run_id`). */
    val ownRunIds: Set<String> = recording.parsedFrames.flatMap { frame ->
        val byRun = (frame["loop_status"] as? JsonObject)?.get("client_message_ids_by_run_id") as? JsonObject
        byRun?.filterValues { fixture.clientMessageId in it.toString() }?.keys.orEmpty()
    }.toSet()
}

internal suspend fun TestScope.parityRuns(fixture: ParityFixture): ParityRuns {
    val recording = fixture.load()
    return ParityRuns(fixture, recording, directRun(recording, fixture), bridgeRun(recording, fixture))
}

internal enum class GateCheck(val verify: (ParityRuns) -> String?) {
    /** Both runs reach a terminal and release their lease. */
    BothRunsEnd(::bothRunsEnd),
    TerminalStatus({ runs -> mismatch("terminal status", runs.direct.outcome.status, runs.bridge.outcome.status) }),
    TerminalReason({ runs -> mismatch("terminal reason", runs.direct.outcome.reason, runs.bridge.outcome.reason) }),
    TerminalRunId({ runs -> mismatch("terminal run id", runs.direct.outcome.runId, runs.bridge.outcome.runId) }),

    /** The direct run ends on the run the App Server's own turn_finished names. */
    DirectMatchesRecording({ runs ->
        mismatch("direct run id vs recorded turn_finished", runs.recordedFinished.parityString("run_id"), runs.direct.outcome.runId)
    }),

    /** terminal delta -> idle loop status -> turn_finished, and turn_finished says what the App Server said. */
    TerminalOrder(::terminalOrder),
    InputAcceptedFirst(::inputAcceptedFirst),

    /** The phone's lease ends on turn_finished, not on the engine's 1.5 s settle window. */
    NoSettleWindow(::noSettleWindow),
    FrameKinds(::frameKinds),

    /** Each of this turn's runs gets its usage_statistics and stop_reason to the phone. */
    RunTailsReachPhone(::runTailsReachPhone),

    /** Per run, usage_statistics before stop_reason, as the App Server sends them. */
    TailOrder(::tailOrder),
    ToolArgumentsAreObjects(::toolArgumentsAreObjects),
}

private fun mismatch(what: String, expected: Any?, actual: Any?): String? =
    if (expected == actual) null else "$what: expected $expected, got $actual"

private fun bothRunsEnd(runs: ParityRuns): String? = when {
    runs.direct.outcome.status == null -> "the direct run never ended"
    runs.bridge.outcome.status == null -> "the phone never ended"
    runs.direct.outcome.busyAfter -> "the direct run kept its lease"
    runs.bridge.outcome.busyAfter -> "the phone kept its lease"
    else -> null
}

private val TERMINAL_DELTA_KINDS = setOf("stop_reason", "error_message", "loop_error")

private fun terminalOrder(runs: ParityRuns): String? {
    val phone = runs.phone
    val terminal = phone.indexOfLast { it.kind in TERMINAL_DELTA_KINDS }
    val idle = phone.indexOfLast { it.type == "update_loop_status" && it.loopStatus == "WAITING_ON_INPUT" }
    val finished = phone.indexOfLast { it.type == "turn_finished" }
    if (!(terminal in 0 until idle && idle < finished)) return "terminal/idle/turn_finished out of order: ${phone.map { it.kind }}"
    val sent = phone[finished].json
    return mismatch("turn_finished stop_reason", runs.recordedFinished.parityString("stop_reason"), sent.parityString("stop_reason"))
        ?: mismatch("turn_finished run_id", runs.recordedFinished.parityString("run_id"), sent.parityString("run_id"))
}

private fun inputAcceptedFirst(runs: ParityRuns): String? {
    val log = runs.bridge.phone.log
    val ack = log.first()
    val accepted = (ack.json["accepted"] as? JsonPrimitive)?.booleanOrNull
    return when {
        ack.channel != WireChannel.Control || ack.type != "input_accepted" -> "first frame is ${ack.json}"
        ack.json.parityString("request_id") != PHONE_REQUEST_ID || accepted != true -> "bad ack ${ack.json}"
        log.count { it.channel == WireChannel.Control } != 1 -> "more than one control frame"
        else -> null
    }
}

private fun noSettleWindow(runs: ParityRuns): String? {
    val waited = runs.bridge.outcome.elapsedMs
    return if (waited < AppServerTurnEngine.DEFAULT_TERMINAL_SETTLE_QUIET_MS) null else "phone lease waited $waited ms"
}

/**
 * App Server frame kinds a phone behind the node does not get, each with its reason. Keep this
 * small and reviewed: a new kind the bridge drops must fail here, not vanish silently.
 */
internal val NODE_ONLY_KINDS = mapOf(
    "update_device_status" to "presence of the node's own App Server connection, not of the phone's turn",
    "control_request" to "the node's engine owns the approval gate; the phone sees the " +
        "approval_request_message delta and answers through admin_rpc approval.submit",
    "external_tool_call_request" to "the node runs host tools itself; the phone sees the call as a tool_call_message",
    "input_accepted" to "the App Server's reply to the node's own approval_response input",
    "abort_message_response" to "the App Server's reply to the node's own abort_message",
    "resume_queue_response" to "the App Server's reply to the node's own resume_queue",
)

/** Kinds the node's engine re-projects before relaying, and the kind the phone gets instead. */
internal val PROJECTED_KINDS = mapOf(
    "approval_request_message" to "tool_call_message", // auto-allowed: the card is suppressed, the call is not
    "client_tool_start" to "tool_call_message",
    "client_tool_end" to "tool_return_message",
)

private fun frameKinds(runs: ParityRuns): String? {
    val bridged = runs.phone.mapNotNull { it.kind }.toSet()
    val recorded = runs.recording.parsedFrames.mapNotNull { WireFrame(WireChannel.Stream, it).kind }.toSet()
    val missing = recorded.filterNot { kind -> kind in bridged || kind in NODE_ONLY_KINDS || PROJECTED_KINDS[kind] in bridged }
    return if (missing.isEmpty()) null else "the bridge dropped $missing (bridged $bridged)"
}

private val TAIL_KINDS = setOf("usage_statistics", "stop_reason")

private fun WireFrame.runId(): String? = (json["delta"] as? JsonObject)?.parityString("run_id")

private fun runTailsReachPhone(runs: ParityRuns): String? {
    val recorded = runs.recording.parsedFrames.map { WireFrame(WireChannel.Stream, it) }
        .filter { it.kind in TAIL_KINDS && it.runId() in runs.ownRunIds }
        .map { it.kind to it.runId() }
    val bridged = runs.phone.map { it.kind to it.runId() }.toSet()
    val missing = recorded.filterNot { it in bridged }
    return if (missing.isEmpty()) null else "the phone never got $missing"
}

private fun tailOrder(runs: ParityRuns): String? {
    val inverted = runs.ownRunIds.filter { run ->
        val usage = runs.phone.indexOfFirst { it.kind == "usage_statistics" && it.runId() == run }
        val stop = runs.phone.indexOfFirst { it.kind == "stop_reason" && it.runId() == run }
        usage >= 0 && stop >= 0 && stop < usage
    }
    return if (inverted.isEmpty()) null else "stop_reason before usage_statistics for $inverted: ${runs.phone.map { it.kind }}"
}

private fun toolArgumentsAreObjects(runs: ParityRuns): String? {
    val bad = runs.phone.mapNotNull { frame ->
        val toolCall = (frame.json["delta"] as? JsonObject)?.get("tool_call") as? JsonObject ?: return@mapNotNull null
        val arguments = (toolCall["arguments"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        arguments.takeUnless { runCatching { AppServerProtocol.json.parseToJsonElement(it) is JsonObject }.getOrDefault(false) }
    }
    return if (bad.isEmpty()) null else "tool arguments that are not a JSON object: $bad"
}
