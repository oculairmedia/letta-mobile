package com.letta.mobile.data.context

import com.letta.mobile.data.transport.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun keepsTheLatestReadingForAConversation() {
        val readings = ContextTokenReadings()

        readings.record(usage(contextTokens = 28_864))
        readings.record(usage(contextTokens = 29_193))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
    }

    @Test
    fun anotherConversationsFrameLeavesThisReadingAlone() {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 29_193))

        readings.record(usage(conversation = CONV_B, contextTokens = 45_117))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
        assertEquals(45_117, readings.latest(AGENT, CONV_B))
    }

    @Test
    fun aFrameWithoutContextTokensDoesNotOverwrite() {
        val readings = ContextTokenReadings()
        readings.record(usage(contextTokens = 29_193))

        readings.record(usage(contextTokens = null, promptTokens = 73))

        assertEquals(29_193, readings.latest(AGENT, CONV_A))
    }

    @Test
    fun promptTokensAreNeverTakenAsTheTotal() {
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

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
