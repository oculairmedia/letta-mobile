package com.letta.mobile.data.context.limit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-joigh: the context-limit slider's stops, clamping, scope and warnings. */
class ContextLimitStopsTest {
    @Test
    fun stopsRunFromTheFloorToTheModelsWindow() {
        val stops = ContextLimitStops.of(modelMax = 1_000_000, current = 128_000)!!
        assertEquals(listOf(32_000, 64_000, 128_000, 200_000, 256_000, 400_000, 1_000_000), stops.values)
        assertEquals(2, stops.indexOf(128_000))
        assertTrue(stops.maxKnown)
    }

    @Test
    fun theModelMaxAndTheCurrentLimitAreAlwaysStops() {
        val stops = ContextLimitStops.of(modelMax = 262_144, current = 150_000)!!
        assertEquals(listOf(32_000, 64_000, 128_000, 150_000, 200_000, 256_000, 262_144), stops.values)
        assertEquals(150_000, stops.valueAt(stops.indexOf(150_000)))
        assertEquals(262_144, stops.valueAt(stops.count - 1))
    }

    @Test
    fun aCurrentLimitAboveTheModelStaysReachableSoItCanBeLowered() {
        val stops = ContextLimitStops.of(modelMax = 200_000, current = 1_000_000)!!
        assertEquals(1_000_000, stops.values.last())
        assertEquals(200_000, stops.values[stops.count - 2])
    }

    @Test
    fun anUnknownWindowRunsToTheDefaultCeilingAndSaysSo() {
        val stops = ContextLimitStops.of(modelMax = null, current = 128_000)!!
        assertEquals(ContextLimitStops.UNKNOWN_MAX_CEILING, stops.values.last())
        assertFalse(stops.maxKnown)
        assertNull(stops.modelMax)
    }

    @Test
    fun aModelAtTheFloorHasNothingToChoose() {
        assertNull(ContextLimitStops.of(modelMax = 32_000, current = 32_000))
        assertNull(ContextLimitStops.of(modelMax = 8_192, current = null))
    }

    @Test
    fun indexingClampsAndSnapsToTheNearestStop() {
        val stops = ContextLimitStops.of(modelMax = 400_000, current = null)!!
        assertEquals(stops.count - 1, stops.indexOf(null))
        assertEquals(128_000, stops.valueAt(stops.indexOf(130_000)))
        assertEquals(32_000, stops.valueAt(-5))
        assertEquals(400_000, stops.valueAt(99))
    }

    @Test
    fun warningsJudgeTheCandidateAgainstUsageAndTheModel() {
        assertEquals(ContextLimitWarning.ExceedsModel, ContextLimitAdvice.of(1_000_000, 50_000, modelMax = 200_000).warning)
        assertEquals(ContextLimitWarning.BelowUsage, ContextLimitAdvice.of(64_000, 112_800, modelMax = 1_000_000).warning)
        // 128k auto-compacts at 128k - 16,384 = 111,616.
        val nearly = ContextLimitAdvice.of(128_000, 112_800, modelMax = 1_000_000)
        assertEquals(ContextLimitWarning.CompactsNextTurn, nearly.warning)
        assertEquals(111_616, nearly.autoCompactAtTokens)
        assertEquals(ContextLimitWarning.None, ContextLimitAdvice.of(400_000, 112_800, modelMax = 1_000_000).warning)
        assertEquals(ContextLimitWarning.None, ContextLimitAdvice.of(64_000, null, modelMax = null).warning)
    }

    @Test
    fun theScopeIsLettaCodesDefaultConversationRule() {
        assertEquals(ContextLimitScope.Agent, ContextLimitScope.forConversation(isDefault = true))
        assertEquals(ContextLimitScope.Conversation, ContextLimitScope.forConversation(isDefault = false))
    }

    @Test
    fun theCommandOutputNamesTheScopeAndTheAppliedValue() {
        val agent = ContextLimitCommandOutput.parse("Agent max context set to 200,000 tokens.", requested = 1)
        assertEquals(200_000, agent.contextWindow)
        assertEquals(ContextLimitScope.Agent, agent.scope)
        val conversation = ContextLimitCommandOutput.parse("Current conversation max context set to 1,000,000 tokens with override.", requested = 1)
        assertEquals(1_000_000, conversation.contextWindow)
        assertEquals(ContextLimitScope.Conversation, conversation.scope)
        val unknown = ContextLimitCommandOutput.parse("ok", requested = 64_000)
        assertEquals(64_000, unknown.contextWindow)
        assertNull(unknown.scope)
    }
}
