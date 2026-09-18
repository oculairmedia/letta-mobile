package com.letta.mobile.data.system1

import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry

/**
 * Preflight hook that runs System 1 over a [TurnCommand] before System 2 sees it.
 *
 * Two jobs:
 *  1. Refuse a turn that System 1 blocks, without paying for an agent turn.
 *  2. Annotate the command with System 1 signals, so downstream model selection
 *     and tool policy can read them off [TurnCommand.metadata].
 *
 * Only [TurnInput.UserMessage] is assessed. A tool-approval response carries no
 * free-text user intent and passes through untouched.
 */
class System1TurnInterceptor(
    private val engine: System1DecisionEngine,
    private val blockingEnabled: Boolean = false,
) {
    /** Outcome of the preflight. */
    sealed interface Decision {
        /** Dispatch [command] — metadata enriched when System 1 answered. */
        data class Proceed(
            val command: TurnCommand,
            val assessment: System1Assessment,
        ) : Decision

        /**
         * System 1 blocked the turn. [assessment] carries the reason; the caller
         * surfaces it instead of dispatching.
         */
        data class Blocked(
            val command: TurnCommand,
            val assessment: System1Assessment,
        ) : Decision
    }

    suspend fun intercept(command: TurnCommand): Decision {
        val input = command.input
        if (input !is TurnInput.UserMessage) {
            return Decision.Proceed(command, System1Assessment.unavailable("not a user message"))
        }

        val assessment = engine.evaluate(input.text)
        emitTelemetry(assessment)

        // An unavailable assessment is not a clearance: blocking on a fallback
        // would turn a System 1 outage into an outage of the whole app.
        val shouldBlock = blockingEnabled &&
            assessment.available &&
            assessment.disposition == System1Disposition.BLOCK

        val enriched = command.copy(metadata = command.metadata + assessment.toMetadata())
        return if (shouldBlock) {
            Decision.Blocked(enriched, assessment)
        } else {
            Decision.Proceed(enriched, assessment)
        }
    }

    private fun emitTelemetry(assessment: System1Assessment) {
        if (!assessment.available) {
            Telemetry.event(TAG, "system1_unavailable", "reason" to assessment.reason)
            return
        }
        Telemetry.event(
            TAG,
            "system1_assessment",
            "disposition" to assessment.disposition.name,
            "domain" to assessment.route.domain,
            "difficulty" to assessment.route.difficultyLabel,
            "jailbreak_prob" to assessment.guard.jailbreakProb,
            "interaction_mode" to assessment.interaction.interactionMode,
            durationMs = assessment.latencyMs.toLong(),
        )
    }

    private companion object {
        const val TAG = "System1"
    }
}

/**
 * System 1 signals as turn metadata.
 *
 * Returns an empty map when the assessment is unavailable, so downstream
 * consumers cannot mistake fallback defaults ("difficulty: trivial", "jailbreak:
 * 0.0") for a real reading.
 */
fun System1Assessment.toMetadata(): Map<String, String> {
    if (!available) return emptyMap()
    return mapOf(
        "system1_disposition" to disposition.name,
        "system1_domain" to route.domain,
        "system1_difficulty" to route.difficultyLabel,
        "system1_needs_tools" to route.needsTools.toString(),
        "system1_jailbreak_prob" to guard.jailbreakProb.toString(),
        "system1_injection_prob" to guard.injectionProb.toString(),
        "system1_interaction_mode" to interaction.interactionMode,
        "system1_latency_ms" to latencyMs.toString(),
    )
}
