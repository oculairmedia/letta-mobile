package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject

import kotlin.time.Duration.Companion.milliseconds
/**
 * Collects and prints per-assertion results for the two-client live-sync probe.
 * One greppable `[iroh-2client]` line per check + a PASS/FAIL summary + a CI
 * exit code driven by [ok].
 */
internal class TwoClientReport(
    private val conversationId: String,
    private val backend: String,
) {
    private data class Check(val name: String, val ok: Boolean, val detail: String)

    private val checks = mutableListOf<Check>()
    private var fatalError: String? = null

    val ok: Boolean get() = fatalError == null && checks.all { it.ok }

    fun record(name: String, result: Result<*>) {
        val ok = result.isSuccess
        val detail = result.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "ok"
        checks += Check(name, ok, detail)
    }

    fun fatal(message: String) {
        fatalError = message
    }

    fun recordCheck(name: String, ok: Boolean, detail: String) {
        checks += Check(name, ok, detail)
    }

    /**
     * Runs ONE round: [sender] sends a turn, and [observer] (a passive viewer)
     * must receive it LIVE. Asserts, in order:
     *   - a user echo row whose content matches the sent text,
     *   - a non-empty, cumulative assistant stream (final text >= first),
     *   - exactly one terminal (turn_done) with a completed status,
     *   - the user echo precedes the assistant text precedes the terminal.
     *
     * All assertions run against the OBSERVER's reduced [ServerFrame] stream —
     * the exact frames the mobile timeline reducer consumes — so a green round
     * proves the observer rendered the other client's turn without initiating it.
     */
    suspend fun observation(
        name: String,
        sender: TwoClientEndpoint,
        observer: TwoClientEndpoint,
        conversationId: String,
        redialCase: Boolean = false,
    ) {
        observer.clearFrames()
        val sentText = "$name payload"
        sender.send(conversationId, sentText)

        // Wait until the observer sees a terminal (or the budget elapses).
        val gotTerminal = withTimeoutOrNull(OBSERVE_BUDGET_MS.milliseconds) {
            while (observer.snapshot().none { it is ServerFrame.TurnDone }) delay(50.milliseconds)
            true
        } == true

        val result = TwoClientObservation.classify(observer.snapshot(), sentText, gotTerminal)
        val detail = buildString {
            append(result.detail)
            if (redialCase) append(" [redial+auto-resubscribe]")
            if (result.violations.isNotEmpty()) append(" violations=${result.violations.joinToString(",")}")
        }
        checks += Check(name, result.violations.isEmpty(), detail)
    }

    fun print() {
        println("[iroh-2client] conversation=$conversationId backend=$backend ok=$ok")
        checks.forEach { check ->
            println("[iroh-2client] check=${check.name} ${if (check.ok) "PASS" else "FAIL"} :: ${check.detail}")
        }
        fatalError?.let { println("[iroh-2client] FATAL :: $it") }
        val failed = checks.filter { !it.ok }.map { it.name }
        if (failed.isNotEmpty()) println("[iroh-2client] failures=${failed.joinToString(",")}")
        println("[iroh-2client] result=${if (ok) "PASS" else "FAIL"}")
    }

    fun toJson(): String {
        val obj = buildJsonObject {
            put("ok", ok)
            put("conversationId", conversationId)
            put("backend", backend)
            fatalError?.let { put("fatal", it) }
            put(
                "checks",
                buildJsonArray {
                    checks.forEach { check ->
                        addJsonObject {
                            put("name", check.name)
                            put("ok", check.ok)
                            put("detail", check.detail)
                        }
                    }
                },
            )
        }
        return Json { encodeDefaults = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    private companion object {
        const val OBSERVE_BUDGET_MS = 60_000L
    }
}

/**
 * Pure classifier for one observer round — no coroutines, no network — so the
 * "did the passive observer render the other client's turn live?" contract is
 * unit-testable. A green result requires exactly: one user echo matching the
 * sent text, a non-empty cumulative assistant stream, exactly one completed
 * terminal, in user -> assistant -> terminal order.
 */
internal object TwoClientObservation {
    data class Result(val violations: List<String>, val detail: String) {
        val ok: Boolean get() = violations.isEmpty()
    }

    fun classify(frames: List<ServerFrame>, sentText: String, gotTerminal: Boolean): Result {
        val userRows = frames.filterIsInstance<ServerFrame.UserMessage>()
        val assistantRows = frames.filterIsInstance<ServerFrame.AssistantMessage>()
        val terminals = frames.filterIsInstance<ServerFrame.TurnDone>()

        val violations = buildList {
            if (!gotTerminal) add("observer_never_received_terminal")
            addAll(userEchoViolations(userRows, sentText))
            addAll(assistantStreamViolations(assistantRows))
            addAll(terminalViolations(terminals))
            addAll(orderingViolations(frames, userRows, sentText, assistantRows, terminals))
        }
        val terminalStatus = terminals.firstOrNull()?.status
        val detail = buildString {
            append("user=${userRows.size} assistant=${assistantRows.size} terminal=${terminals.size} ")
            append("finalAssistant='${assistantRows.lastOrNull()?.content?.take(40) ?: ""}' ")
            append("terminalStatus=${terminalStatus ?: "NA"}")
        }
        return Result(violations, detail)
    }

    private fun userEcho(userRows: List<ServerFrame.UserMessage>, sentText: String): ServerFrame.UserMessage? =
        userRows.lastOrNull { it.content.contains(sentText) } ?: userRows.lastOrNull()

    private fun userEchoViolations(userRows: List<ServerFrame.UserMessage>, sentText: String): List<String> {
        val echo = userEcho(userRows, sentText) ?: return listOf("observer_missing_user_echo")
        if (!echo.content.contains(sentText)) return listOf("user_echo_text_mismatch:${echo.content.take(40)}")
        return emptyList()
    }

    private fun assistantStreamViolations(assistantRows: List<ServerFrame.AssistantMessage>): List<String> {
        if (assistantRows.isEmpty()) return listOf("observer_missing_assistant_stream")
        val first = assistantRows.first().content
        val last = assistantRows.last().content
        return buildList {
            if (last.isEmpty()) add("assistant_final_text_empty")
            if (last.length < first.length) add("assistant_stream_not_cumulative:${first.length}->${last.length}")
        }
    }

    private fun terminalViolations(terminals: List<ServerFrame.TurnDone>): List<String> = buildList {
        if (terminals.size != 1) add("terminal_count_${terminals.size}")
        val status = terminals.firstOrNull()?.status
        if (terminals.isNotEmpty() && status != "completed") add("terminal_status_${status ?: "missing"}")
    }

    private fun orderingViolations(
        frames: List<ServerFrame>,
        userRows: List<ServerFrame.UserMessage>,
        sentText: String,
        assistantRows: List<ServerFrame.AssistantMessage>,
        terminals: List<ServerFrame.TurnDone>,
    ): List<String> {
        val echo = userEcho(userRows, sentText) ?: return emptyList()
        if (assistantRows.isEmpty() || terminals.isEmpty()) return emptyList()
        val userIdx = frames.indexOf(echo)
        val asstIdx = frames.indexOf(assistantRows.last())
        val termIdx = frames.indexOf(terminals.first())
        if (userIdx <= asstIdx && asstIdx <= termIdx) return emptyList()
        return listOf("out_of_order:user=$userIdx asst=$asstIdx term=$termIdx")
    }
}
