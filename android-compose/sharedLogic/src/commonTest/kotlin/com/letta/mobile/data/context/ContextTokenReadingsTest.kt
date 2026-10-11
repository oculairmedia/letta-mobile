package com.letta.mobile.data.context

import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import com.letta.mobile.runtime.CompactionStats
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * letta-mobile-r2zo8: the latest `usage_statistics.context_tokens` per conversation. Numbers are
 * the ones measured on a fresh chat on 2026-10-03.
 */
class ContextTokenReadingsTest {
    private fun usage(
        conversation: String? = CONV_A,
        contextTokens: Long? = null,
        agent: String? = AGENT,
        promptTokens: Long = 0,
    ) = ServerFrame.UsageStatistics(
        agentId = agent,
        conversationId = conversation,
        contextTokens = contextTokens,
        promptTokens = promptTokens,
    )

    @Test
    fun keepsTheLatestReadingForAConversation() = runTest {
        val readings = ContextTokenReadings()

        readings.record(usage(contextTokens = 28_864))
        readings.record(usage(contextTokens = 29_193))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
    }

    @Test
    fun anotherConversationsFrameLeavesThisReadingAlone() = runTest {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 29_193))

        readings.record(usage(conversation = CONV_B, contextTokens = 45_117))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
        assertEquals(45_117, readings.latest(AGENT, CONV_B))
    }

    @Test
    fun aFrameWithoutContextTokensDoesNotOverwrite() = runTest {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 29_193))

        readings.record(usage(contextTokens = null, promptTokens = 73))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
    }

    @Test
    fun promptTokensAreNeverTakenAsTheTotal() = runTest {
        val readings = ContextTokenReadings()

        readings.record(usage(contextTokens = null, promptTokens = 73))

        assertNull(readings.latest(AGENT, CONV_A))
    }

    @Test
    fun aFrameThatDoesNotNameItsConversationIsIgnored() {
        val before = mapOf(ContextReadingKey(AGENT, CONV_A) to 29_193)

        assertSame(before, reduceContextReadings(before, usage(conversation = null, contextTokens = 1)))
        assertSame(before, reduceContextReadings(before, usage(agent = " ", contextTokens = 1)))
        assertSame(before, reduceContextReadings(before, ServerFrame.TurnStarted(
            id = "f", ts = "", agentId = AGENT, conversationId = CONV_A, turnId = "turn-1", runId = "run-1",
        )))
    }

    @Test
    fun aReadingIsLookedUpOnlyWithBothHalvesOfItsIdentity() {
        val readings = mapOf(ContextReadingKey(AGENT, CONV_A) to 29_193)

        assertNull(readings.readingFor(null, CONV_A))
        assertNull(readings.readingFor(AGENT, null))
        assertNull(readings.readingFor("agent-other", CONV_A))
    }

    @Test
    fun aDefaultConversationFrameStampedBareReachesTheAppsDefaultConversation() = runTest {
        // The App Server names an agent's default conversation `default`; the app opens it as
        // `conv-default-<agentId>`. The chip asks with the app's form.
        val readings = ContextTokenReadings()

        readings.record(usage(conversation = "default", contextTokens = 28_864))

        assertEquals(28_864, readings.latest(AGENT, "conv-default-$AGENT"))
        assertEquals(28_864, readings.latest(AGENT, "default"))
    }

    @Test
    fun aDefaultConversationFrameStampedInTheAppsFormIsReadTheSame() = runTest {
        val readings = ContextTokenReadings()

        readings.record(usage(conversation = "conv-default-$AGENT", contextTokens = 29_193))

        assertEquals(29_193, readings.latest(AGENT, "conv-default-$AGENT"))
    }

    @Test
    fun anotherAgentsDefaultConversationIsNotThisOne() = runTest {
        val readings = ContextTokenReadings()

        readings.record(usage(agent = "agent-other", conversation = "default", contextTokens = 45_117))

        assertNull(readings.latest(AGENT, "conv-default-$AGENT"))
        assertNull(readings.latest(AGENT, "default"))
        assertEquals(45_117, readings.latest("agent-other", "conv-default-agent-other"))
    }

    @Test
    fun aRealConversationIdIsKeptVerbatim() = runTest {
        assertEquals(ContextReadingKey(AGENT, "local-conv-575"), contextReadingKeyOf(AGENT, "local-conv-575"))
        val readings = ContextTokenReadings()
        readings.record(usage(conversation = "local-conv-575", contextTokens = 45_515))

        assertNull(readings.latest(AGENT, "local-conv-576"))
        assertNull(readings.latest(AGENT, "conv-default-$AGENT"))
    }

    private fun compacted(before: Long?, after: Long?, conversation: String = CONV_A) = ServerFrame.RunActivity(
        id = "ra-1",
        ts = "2026-10-09T10:00:00Z",
        agentId = AGENT,
        conversationId = conversation,
        payload = RuntimeEventPayload.CompactionFinished(
            summary = "s",
            stats = CompactionStats(contextTokensBefore = before, contextTokensAfter = after),
        ),
    )

    @Test
    fun aCompactionTakesItsDropOffTheLastTotalAndFlagsItEstimated() = runTest {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 160_000))

        readings.record(compacted(before = 150_000, after = 20_000))

        assertEquals(30_000, readings.latest(AGENT, CONV_A))
        assertTrue(readings.isEstimated(AGENT, CONV_A))
    }

    @Test
    fun theNextProviderTotalIsExactAgain() = runTest {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 160_000))
        readings.record(compacted(before = 150_000, after = 20_000))

        readings.record(usage(contextTokens = 31_500))

        assertEquals(31_500, readings.latest(AGENT, CONV_A))
        assertFalse(readings.isEstimated(AGENT, CONV_A))
    }

    @Test
    fun theEstimateNeverFallsBelowTheTranscriptNorRisesAboveTheLastTotal() = runTest {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 40_000))
        readings.record(compacted(before = 150_000, after = 20_000))
        assertEquals(20_000, readings.latest(AGENT, CONV_A))

        val grown = ContextTokenReadings()
        grown.record(usage(contextTokens = 40_000))
        grown.record(compacted(before = 10_000, after = 12_000))
        assertEquals(40_000, grown.latest(AGENT, CONV_A))
    }

    @Test
    fun aCompactionWithoutAPriorTotalOrStatsWritesNothing() = runTest {
        val readings = ContextTokenReadings()
        readings.record(compacted(before = 150_000, after = 20_000))
        assertNull(readings.latest(AGENT, CONV_A))
        assertFalse(readings.isEstimated(AGENT, CONV_A))

        readings.record(usage(contextTokens = 160_000))
        readings.record(compacted(before = null, after = 20_000))
        assertEquals(160_000, readings.latest(AGENT, CONV_A))
        assertFalse(readings.isEstimated(AGENT, CONV_A))
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
