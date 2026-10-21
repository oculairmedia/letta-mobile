package com.letta.mobile.data.context

import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composer meter keeps the open conversation's real breakdown loaded in the background,
 * with no context card open, from the same loader the card reads.
 */
class ContextBreakdownPrefetchTest {
    @Test
    fun aSettledOpenConversationFetchesItsBreakdownAndTheMeterShowsIt() = runTest {
        val asked = mutableListOf<ContextBreakdownRequest>()
        val loader = ContextBreakdownLoader { request -> asked += request; overview(used = 42_000) }

        val shown = flowOf(settled(CONVERSATION_A, tokens = 41_000))
            .contextUsageStates(loader)
            .first { it.usage?.segments?.any { segment -> segment.kind == ContextWindowSegmentKind.Messages } == true }

        assertEquals(listOf(ConversationId(CONVERSATION_A)), asked.map { it.conversationId })
        assertEquals(41_000, asked.single().reportedTotal)
        // The breakdown's numbers, not the streamed estimate's.
        assertEquals(ContextWindowUsage.from(overview(used = 42_000)), shown.usage)
        assertEquals(overview(used = 42_000), loader.breakdowns.value[asked.single().key])
    }

    @Test
    fun movingToAnotherConversationCancelsTheStaleFetch() = runTest {
        val focus = MutableStateFlow(settled(CONVERSATION_A, tokens = 10_000))
        val firstStarted = CompletableDeferred<Unit>()
        var firstCancelled = false
        val loader = ContextBreakdownLoader { request ->
            if (request.conversationId == ConversationId(CONVERSATION_A)) {
                firstStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    firstCancelled = true
                }
            }
            overview(used = 20_000)
        }
        val shown = async(start = CoroutineStart.UNDISPATCHED) {
            focus.contextUsageStates(loader).first { it.usage?.usedTokens == 20_000 }
        }
        firstStarted.await()

        focus.value = settled(CONVERSATION_B, tokens = 19_000)

        assertEquals(20_000, shown.await().usage?.usedTokens)
        assertTrue(firstCancelled, "the fetch for the conversation left behind is cancelled")
        assertEquals(setOf(ConversationId(CONVERSATION_B)), loader.breakdowns.value.keys.map { it.conversationId }.toSet())
    }

    @Test
    fun aRevisitedConversationShowsItsLastBreakdownWhileItRefreshes() = runTest {
        val focus = MutableStateFlow(settled(CONVERSATION_A, tokens = 30_000))
        var answers = 0
        val hold = CompletableDeferred<Unit>()
        val loader = ContextBreakdownLoader { request ->
            answers += 1
            // The refresh on the way back never lands in this test.
            if (answers > 2) hold.await()
            overview(used = if (request.conversationId == ConversationId(CONVERSATION_A)) 31_000 else 5_000)
        }
        val states = mutableListOf<ContextWindowUsageState>()
        val collector = launch { focus.contextUsageStates(loader).collect { states += it } }
        loader.breakdowns.first { it.size == 1 }
        focus.value = settled(CONVERSATION_B, tokens = 4_000)
        loader.breakdowns.first { it.size == 2 }

        focus.value = settled(CONVERSATION_A, tokens = 30_500)
        runCurrent()

        assertEquals(31_000, states.last().usage?.usedTokens)
        collector.cancel()
    }

    @Test
    fun aMeterTravelsBetweenReadingsInsteadOfJumping() {
        val from = ContextWindowUsage.total(usedTokens = 40_000, windowTokens = 200_000)
        val to = ContextWindowUsage.from(overview(used = 80_000))

        val halfway = from.towards(to, 0.5f)

        assertEquals(60_000, halfway.usedTokens)
        assertEquals(200_000, halfway.maxTokens)
        assertEquals(halfway.usedTokens, halfway.segments.sumOf { it.tokens })
        assertEquals(to, from.towards(to, 1f))
        assertEquals(from, from.towards(from, 0.3f))
    }

    private fun settled(conversation: String, tokens: Int) = ContextReadingInputs(
        key = ContextWindowUsageKey(AGENT, conversation, settled = true),
        contextTokens = tokens,
        windowTokens = 200_000,
    )

    private fun overview(used: Int) = ContextWindowOverview(
        contextWindowSizeMax = 200_000,
        contextWindowSizeCurrent = used,
        numTokensSystem = 2_000,
        numTokensMessages = used - 2_000,
    )

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION_A = "conv-a"
        const val CONVERSATION_B = "conv-b"
    }
}
