package com.letta.mobile.cli.commands

import com.letta.mobile.cli.probe.NoHttpSocketScan
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.time.Duration.Companion.milliseconds
internal class LegacyProbeScenarios(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
) {
    suspend fun run(scenarioSet: Set<String>, target: ProbeTarget): List<IrohProbeTurnMetrics> {
        if (scenarioSet.skipsLegacyProbeTurns()) return emptyList()

        val turns = mutableListOf<IrohProbeTurnMetrics>()
        turns += if ("idle-send" in scenarioSet) {
            runIdleSend(target, scenarioSet)
        } else {
            runDefaultTurns(target, scenarioSet)
        }
        if (scenarioSet.includesPostIdleRestart()) {
            turns += runPostIdleRestart(target, turns.size + 1)
        }
        return turns
    }

    private suspend fun runDefaultTurns(
        target: ProbeTarget,
        scenarioSet: Set<String>,
    ): List<IrohProbeTurnMetrics> {
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        turns += fixture.runProbeTurn(
            turn = 1,
            target = target,
            runAdminRpcScenario = "admin-rpc" in scenarioSet,
        )
        if ("restart-send" in scenarioSet) {
            runWrapperRestart()?.let { turns += restartSkippedTurn(turn = 2, note = it) }
        } else {
            delay(options.secondTurnDelayMs.milliseconds)
        }
        if (turns.none { it.turn == 2 }) {
            turns += fixture.runProbeTurn(2, target)
        }
        return turns
    }

    private suspend fun runPostIdleRestart(target: ProbeTarget, turn: Int): IrohProbeTurnMetrics =
        runWrapperRestart()?.let { restartSkippedTurn(turn, it) }
            ?: fixture.runProbeTurn(turn, target)

    private suspend fun runIdleSend(
        target: ProbeTarget,
        scenarioSet: Set<String>,
    ): List<IrohProbeTurnMetrics> {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        val turnStartedAt = nowMs()
        try {
            val setup = withTimeoutOrNull(options.timeoutMs.milliseconds) {
                fixture.establish(
                    ProbeEstablishRequest(
                        target = target,
                        scope = scope,
                        turn = 1,
                        runAdminRpcScenario = "admin-rpc" in scenarioSet,
                        turnStartedAt = turnStartedAt,
                    ),
                )
            }
            if (setup == null) {
                turns += idleSetupTimeoutTurn()
                return turns
            }
            session = setup
            val firstTurn = fixture.sendProbeInput(
                ProbeSendInputRequest(turn = 1, session = session, turnStartedAt = turnStartedAt),
            )
            turns += firstTurn
            delay(options.idleMs.milliseconds)
            val secondTurn = fixture.sendProbeInput(
                ProbeSendInputRequest(turn = 2, session = session, sendFailureViolation = true),
            )
            turns += withCrossTurnViolation(firstTurn, secondTurn)
        } catch (error: Throwable) {
            turns += idleSendFailureTurn(turns.size, error)
        } finally {
            fixture.close(session, scope)
        }
        return turns
    }

    private fun idleSetupTimeoutTurn(): IrohProbeTurnMetrics {
        val violation = IrohProbeAssertions.classifyIdleSendFailure("setup timeout after ${options.timeoutMs}ms")
        return IrohProbeTurnMetrics(
            turn = 1,
            errorFrames = listOf(violation),
            scenarioViolations = listOf(violation),
            timedOut = true,
        )
    }

    private fun withCrossTurnViolation(
        firstTurn: IrohProbeTurnMetrics,
        secondTurn: IrohProbeTurnMetrics,
    ): IrohProbeTurnMetrics {
        val crossTurn = IrohProbeAssertions.classifyCrossTurnEventSeq(firstTurn.eventSeqs, secondTurn.eventSeqs)
        return if (crossTurn == null) secondTurn else {
            secondTurn.copy(scenarioViolations = secondTurn.scenarioViolations + crossTurn)
        }
    }

    private fun idleSendFailureTurn(priorTurns: Int, error: Throwable): IrohProbeTurnMetrics {
        val message = error.message ?: error.toString()
        val violation = if (message.contains("Conversation not found", ignoreCase = true)) {
            IrohProbeAssertions.classifyConversationBootstrap(message)
        } else {
            IrohProbeAssertions.classifyIdleSendFailure(message)
        }
        return IrohProbeTurnMetrics(
            turn = (priorTurns + 1).coerceAtLeast(1),
            errorFrames = listOf(violation),
            scenarioViolations = listOf(violation),
            timedOut = false,
        )
    }

    private fun runWrapperRestart(): String? {
        val command = options.wrapperRestartCmd?.takeIf { it.isNotBlank() }
            ?: return "restart-send skipped: --wrapper-restart-cmd not provided"
        val exit = ProcessBuilder("bash", "-lc", command).inheritIO().start().waitFor()
        if (exit != 0) error("wrapper restart command failed with exit code $exit")
        return null
    }

    private fun restartSkippedTurn(turn: Int, note: String) = IrohProbeTurnMetrics(
        turn = turn,
        assistantDeltaCount = 1,
        assistantMessageIds = listOf("restart-send-skipped"),
        turnDoneCount = 1,
        notes = listOf(note),
        skipped = true,
    )
}

private val legacyProbeScenarios = setOf("admin-rpc", "idle-send", "restart-send")

/** True when the caller only asked for non-legacy scenarios (hydrate/cancel/etc.). */
private fun Set<String>.skipsLegacyProbeTurns(): Boolean {
    if (isEmpty()) return false
    val legacy = this intersect legacyProbeScenarios
    if (legacy.isNotEmpty()) return false
    return (this - legacy).isNotEmpty()
}

private fun Set<String>.includesPostIdleRestart(): Boolean {
    if ("restart-send" !in this) return false
    return "idle-send" in this
}

internal class NoHttpProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(target: ProbeTarget): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        val samples: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val scanUnsupported = AtomicBoolean(false)
        fun sample() {
            when (val count = NoHttpSocketScan.connectionsToPort(admin.adminPort())) {
                null -> scanUnsupported.set(true)
                else -> samples += count
            }
        }
        val turnStartedAt = nowMs()
        val sampler = scope.launch {
            while (true) {
                sample()
                delay(100.milliseconds)
            }
        }
        return try {
            sample()
            val metrics = withTimeoutOrNull(options.timeoutMs.milliseconds) {
                val established = fixture.establish(
                    ProbeEstablishRequest(
                        target = target,
                        scope = scope,
                        turn = 1,
                        runAdminRpcScenario = false,
                        turnStartedAt = turnStartedAt,
                    ),
                )
                session = established
                sample()
                val turnMetrics = fixture.sendProbeInput(
                    ProbeSendInputRequest(
                        turn = 1,
                        session = established,
                        turnStartedAt = turnStartedAt,
                        scenario = ProbeScenarioName.NoHttp,
                    ),
                )
                sample()
                turnMetrics
            } ?: IrohProbeTurnMetrics(
                turn = 1,
                scenario = ProbeScenarioName.NoHttp.value,
                timedOut = true,
                scenarioViolations = listOf("no_http_setup_timeout"),
            )
            sampler.cancelAndJoin()
            annotateNoHttp(metrics, samples.toList(), scanUnsupported.get())
        } finally {
            runCatching { sampler.cancel() }
            fixture.close(session, scope)
        }
    }

    private fun annotateNoHttp(
        metrics: IrohProbeTurnMetrics,
        snapshot: List<Int>,
        scanUnsupported: Boolean,
    ): IrohProbeTurnMetrics = metrics.copy(
        scenarioViolations = metrics.scenarioViolations +
            listOfNotNull(IrohProbeAssertions.classifyNoHttp(snapshot)),
        notes = metrics.notes +
            (if (scanUnsupported) listOf("no_http_scan_unsupported_platform") else emptyList()) +
            "no_http_socket_samples=${snapshot.size} max=${snapshot.maxOrNull() ?: -1}",
    )
}

