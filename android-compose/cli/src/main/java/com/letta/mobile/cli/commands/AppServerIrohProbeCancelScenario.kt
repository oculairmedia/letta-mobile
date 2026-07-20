package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import kotlin.time.Duration.Companion.milliseconds
internal class CancelMidstreamProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(target: ProbeTarget): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        var abortJob: Job? = null
        val turnStartedAt = nowMs()
        return try {
            withTimeoutOrNull(options.timeoutMs.milliseconds) {
                val established = fixture.establish(
                    ProbeEstablishRequest(
                        target = target,
                        scope = scope,
                        turn = 1,
                        turnStartedAt = turnStartedAt,
                    ),
                )
                session = established
                runCancelFlow(established, turnStartedAt) { abortJob = it }
            } ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = ProbeScenarioName.CancelMidstream.value,
                profile = IrohProbeAssertions.PROFILE_CANCEL,
                timedOut = true,
            )
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 1,
                scenario = ProbeScenarioName.CancelMidstream.value,
                profile = IrohProbeAssertions.PROFILE_CANCEL,
                scenarioViolations = listOf("cancel_failed:${error.message ?: error}"),
            )
        } finally {
            runCatching { abortJob?.cancel() }
            fixture.close(session, scope)
        }
    }

    private suspend fun runCancelFlow(
        established: ProbeSession,
        turnStartedAt: Long,
        onAbortJob: (Job) -> Unit,
    ): IrohProbeTurnMetrics {
        val observed = ProbeAccumulator(turn = 1, dumpPath = options.dumpPath())
        var firstFrameMs: Long? = null
        val collector = established.scope.launch {
            established.client.events.collect { received ->
                val inbound = received.frame
                if (!inbound.matches(established.runtime)) return@collect
                if (firstFrameMs == null) firstFrameMs = nowMs() - turnStartedAt
                observed.record(inbound)
            }
        }
        fixture.sendInputFrame(established, ProbeClientMessageId("probe-cancel-${UUID.randomUUID()}"))
        while (observed.activeRunId == null && observed.terminalCount == 0) delay(20.milliseconds)
        val runId = observed.activeRunId
        if (runId != null) {
            onAbortJob(launchAbort(established, runId))
        }
        while (observed.terminalCount == 0) delay(50.milliseconds)
        delay(500.milliseconds)
        collector.cancelAndJoin()
        val outcome = evaluateCancelOutcome(runId)
        return observed.toMetrics(
            ProbeMetricsRequest(
                dialMs = established.dialMs,
                firstFrameMs = firstFrameMs,
                timedOut = false,
                scenario = ProbeScenarioName.CancelMidstream,
                profile = IrohProbeAssertions.PROFILE_CANCEL,
                extraViolations = outcome.violations,
                extraNotes = outcome.notes,
            ),
        )
    }

    private fun launchAbort(established: ProbeSession, runId: String): Job =
        established.scope.launch {
            runCatching {
                established.client.abort(
                    AppServerCommand.AbortMessage(
                        runtime = established.runtime,
                        requestId = "probe-abort-${UUID.randomUUID()}",
                        runId = runId,
                    ),
                )
            }
        }

    private data class CancelOutcome(val violations: List<String>, val notes: List<String>)

    private suspend fun evaluateCancelOutcome(runId: String?): CancelOutcome {
        val violations = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (runId == null) {
            violations += "cancel_no_run_id_before_terminal"
            return CancelOutcome(violations, notes)
        }
        when (val status = pollRunStatus(runId, 5_000)) {
            null -> notes += "cancel_run_status_unverified:admin_base_unreachable"
            "running" -> violations += "cancel_run_still_running_after_5s"
            else -> notes += "cancel_run_status=$status"
        }
        return CancelOutcome(violations, notes)
    }

    private suspend fun pollRunStatus(runId: String, deadlineMs: Long): String? {
        val deadline = nowMs() + deadlineMs
        var lastStatus: String? = null
        while (nowMs() < deadline) {
            val status = runCatching {
                admin.json(ProbeHttpMethod.Get, ProbeHttpPath("/v1/runs/$runId"))
                    .jsonObject["status"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (status == null) {
                delay(250.milliseconds)
                continue
            }
            lastStatus = status
            if (status != "running") return status
            delay(250.milliseconds)
        }
        return lastStatus
    }
}
