package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

internal class DuplicateSendProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
) {
    suspend fun run(target: ProbeTarget): List<IrohProbeTurnMetrics> {
        val clientMessageId = ProbeClientMessageId("probe-dup-${UUID.randomUUID()}")
        val turns = mutableListOf<IrohProbeTurnMetrics>()
        turns += runSameConnectionPhase(target, clientMessageId)
        turns += runRedialPhase(target, clientMessageId)
        return turns
    }

    private suspend fun runSameConnectionPhase(
        target: ProbeTarget,
        clientMessageId: ProbeClientMessageId,
    ): List<IrohProbeTurnMetrics> {
        val firstScope = newProbeScope()
        var firstSession: ProbeSession? = null
        return try {
            withTimeoutOrNull(options.timeoutMs * 2) {
                val established = fixture.establish(
                    ProbeEstablishRequest(target = target, scope = firstScope, turn = 1),
                )
                firstSession = established
                val first = fixture.sendProbeInput(
                    ProbeSendInputRequest(
                        turn = 1,
                        session = established,
                        scenario = ProbeScenarioName.DuplicateSend,
                        clientMessageId = clientMessageId,
                    ),
                )
                val replay = fixture.observeTerminals(established, GRACE_MS) {
                    fixture.sendInputFrame(established, clientMessageId)
                }
                listOf(first, sameConnectionReplayTurn(established, replay))
            } ?: listOf(duplicateTimeoutTurn(1, "duplicate_send_setup_timeout"))
        } catch (error: Throwable) {
            listOf(
                IrohProbeTurnMetrics(
                    turn = 1,
                    scenario = ProbeScenarioName.DuplicateSend.value,
                    scenarioViolations = listOf("duplicate_send_failed:${error.message ?: error}"),
                ),
            )
        } finally {
            fixture.close(firstSession, firstScope)
        }
    }

    private fun sameConnectionReplayTurn(
        established: ProbeSession,
        replay: ProbeAccumulator,
    ): IrohProbeTurnMetrics {
        val terminals = replay.terminalCount
        return IrohProbeTurnMetrics(
            turn = 2,
            scenario = ProbeScenarioName.DuplicateSend.value,
            profile = IrohProbeAssertions.PROFILE_REPORT,
            dialMs = established.dialMs,
            turnDoneCount = terminals,
            eventSeqs = replay.observedEventSeqs,
            untypedFrameCount = replay.untypedFrameCount,
            scenarioViolations = listOfNotNull(
                IrohProbeAssertions.classifyDuplicateSend(1 + terminals, "same-connection"),
            ),
            notes = listOf("duplicate_send_same_connection_replay_terminals=$terminals"),
        )
    }

    private suspend fun runRedialPhase(
        target: ProbeTarget,
        clientMessageId: ProbeClientMessageId,
    ): IrohProbeTurnMetrics {
        val redialScope = newProbeScope()
        var redialSession: ProbeSession? = null
        return try {
            withTimeoutOrNull(options.timeoutMs) {
                val established = fixture.establish(
                    ProbeEstablishRequest(target = target, scope = redialScope, turn = 3),
                )
                redialSession = established
                val replay = fixture.observeTerminals(established, GRACE_MS) {
                    fixture.sendInputFrame(established, clientMessageId)
                }
                redialReplayTurn(established, replay)
            } ?: duplicateTimeoutTurn(3, "duplicate_send_redial_timeout")
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 3,
                scenario = ProbeScenarioName.DuplicateSend.value,
                scenarioViolations = listOf("duplicate_send_redial_failed:${error.message ?: error}"),
            )
        } finally {
            fixture.close(redialSession, redialScope)
        }
    }

    private fun redialReplayTurn(
        established: ProbeSession,
        replay: ProbeAccumulator,
    ): IrohProbeTurnMetrics {
        val terminals = replay.terminalCount
        val violation = IrohProbeAssertions.classifyDuplicateSend(1 + terminals, "after-redial")
        return IrohProbeTurnMetrics(
            turn = 3,
            scenario = ProbeScenarioName.DuplicateSend.value,
            profile = IrohProbeAssertions.PROFILE_REPORT,
            dialMs = established.dialMs,
            turnDoneCount = terminals,
            eventSeqs = replay.observedEventSeqs,
            untypedFrameCount = replay.untypedFrameCount,
            scenarioViolations = if (options.strictRedialDedupe) listOfNotNull(violation) else emptyList(),
            notes = buildList {
                add("duplicate_send_redial_replay_terminals=$terminals")
                if (!options.strictRedialDedupe && violation != null) {
                    add("known_3wq5g_redial_dedupe_gap:$violation (enforced after P3 via --strict-redial-dedupe)")
                }
            },
        )
    }

    private fun duplicateTimeoutTurn(turn: Int, violation: String) = IrohProbeTurnMetrics(
        turn = turn,
        scenario = ProbeScenarioName.DuplicateSend.value,
        timedOut = true,
        scenarioViolations = listOf(violation),
    )

    private companion object {
        const val GRACE_MS = 3_000L
    }
}
