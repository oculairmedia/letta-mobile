package com.letta.mobile.data.timeline

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-dzt3g: every real captured turn (letta-mobile-bglj6.1.15), replayed from its raw wire
 * lines through the phone's observer mapping, the real coordinator and presentation, then settled
 * into the stored rows it produced - checked on every frame by [timelineInvariantViolations].
 *
 * One test per model keeps each run short. Turn 1 of each model is replayed; claude and grok also
 * replay turn 2 over turn 1's history. Frames without a host stamp get one from [ObserverFrameReplay]. Every model is clean.
 */
class RealTurnReplayTest {
    @Test fun realClaudeSonnet55TurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("claude-sonnet-5-5", bothTurns = true)

    @Test fun realKatCoderProV25TurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("kat-coder-pro-v2.5")

    @Test fun realMinimaxM3TurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("minimax-m3")

    @Test fun realMinimaxM3FallbackFromKatCoderTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("minimax-m3-fallback-from-kat-coder")

    @Test fun realMinimaxM3FallbackFromQwen38MaxTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("minimax-m3-fallback-from-qwen3.8-max")

    @Test fun realOpenrouterDeepseekV41FlashTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-deepseek-v4.1-flash")

    @Test fun realOpenrouterGemini38FlashTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-gemini-3.8-flash")

    @Test fun realOpenrouterGlm53FlashTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-glm-5.3-flash")

    @Test fun realOpenrouterGpt61SolTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-gpt-6.1-sol")

    @Test fun realOpenrouterGrok47TurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-grok-4.7", bothTurns = true)

    @Test fun realOpenrouterQwen38FlashTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("openrouter-qwen3.8-flash")

    @Test fun realQwen38MaxTurnsHoldTheIdentityInvariants() = assertModelHoldsInvariants("qwen3.8-max")

    private fun assertModelHoldsInvariants(model: String, bothTurns: Boolean = false) = runBlocking {
        val violations = RealTurnCaptures.model(model, bothTurns).associate { it.name to replay(it) }.filterValues { it.isNotEmpty() }
        assertTrue(violations.isEmpty(), "turns that violate an invariant:\n" + violations.format())
    }

    private suspend fun replay(turn: RealTurn): List<String> {
        val recorder = TimelineFrameRecorder.open(turn.scope)
        try {
            if (turn.history.isNotEmpty()) recorder.openOnHistory(turn.history)
            recorder.streamRaw(turn.wire, finished = true)
            recorder.settle(turn.settled)
            return timelineInvariantViolations(recorder.frames)
        } finally {
            recorder.close()
        }
    }

    private fun Map<String, List<String>>.format(): String =
        entries.joinToString("\n") { (name, found) ->
            "$name (${found.size}):\n  " + found.take(MAX_SHOWN).joinToString("\n  ") { it.take(MAX_LINE) }
        }

    private companion object {
        const val MAX_SHOWN = 6
        const val MAX_LINE = 300
    }
}
