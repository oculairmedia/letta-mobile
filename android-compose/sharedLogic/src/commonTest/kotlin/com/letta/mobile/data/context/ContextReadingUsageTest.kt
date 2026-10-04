package com.letta.mobile.data.context

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LlmConfig
import com.letta.mobile.data.model.LlmModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-r2zo8 / letta-mobile-0ofhc: a streamed total, mapped to the chip by the shared policy. */
class ContextReadingUsageTest {
    private fun inputs(
        conversation: String? = CONV_A,
        tokens: Int? = 29_193,
        settled: Boolean = true,
        window: Int? = 128_000,
    ) = ContextReadingInputs(ContextWindowUsageKey(AGENT, conversation, settled), tokens, window)

    @Test
    fun aTotalOnlyReadingMapsToUsedAndMaxWithNoCategoryRows() {
        val usage = ContextReadingDisplay().advance(inputs()).state.usage

        assertEquals(29_193, usage?.usedTokens)
        assertEquals(128_000, usage?.maxTokens)
        assertEquals(98_807, usage?.freeTokens)
        assertEquals(listOf(ContextWindowUsage.IN_CONTEXT_LABEL), usage?.segments?.map { it.label })
        assertEquals(listOf(ContextWindowSegmentKind.Unitemised), usage?.segments?.map { it.kind })
    }

    @Test
    fun switchingConversationClearsTheReading() {
        val shown = ContextReadingDisplay().advance(inputs())

        val switched = shown.advance(inputs(conversation = CONV_B, tokens = null))

        assertNull(switched.state.usage)
        assertEquals(ContextWindowUsagePolicy.cleared(), switched.state)
    }

    @Test
    fun switchingMidTurnNeverShowsTheOtherConversationsNumber() {
        val shown = ContextReadingDisplay().advance(inputs())

        val switched = shown.advance(inputs(conversation = CONV_B, tokens = 45_117, settled = false))

        assertNull(switched.state.usage)
    }

    @Test
    fun aNewNumberWaitsForTheTurnToSettle() {
        val shown = ContextReadingDisplay().advance(inputs(tokens = 28_864))

        val midTurn = shown.advance(inputs(tokens = 29_193, settled = false))
        val settled = midTurn.advance(inputs(tokens = 29_193, settled = true))

        assertEquals(28_864, midTurn.state.usage?.usedTokens)
        assertEquals(29_193, settled.state.usage?.usedTokens)
    }

    @Test
    fun keepsTheLastGoodReadingWhenTheNumberGoesAway() {
        val shown = ContextReadingDisplay().advance(inputs())

        val lost = shown.advance(inputs(tokens = null))

        assertEquals(29_193, lost.state.usage?.usedTokens)
    }

    @Test
    fun noReadingYetShowsThePlaceholder() {
        val state = ContextReadingDisplay().advance(inputs(tokens = null)).state

        assertEquals(ContextWindowUsageState(), state)
    }

    @Test
    fun anUnknownWindowStillReportsTheTotal() {
        val usage = ContextReadingDisplay().advance(inputs(window = null)).state.usage

        assertEquals(29_193, usage?.usedTokens)
        assertEquals(0, usage?.maxTokens)
        assertEquals(0, usage?.freeTokens)
    }

    @Test
    fun theFlowFoldAppliesTheSameRules() = runTest {
        val states = flowOf(
            inputs(tokens = null),
            inputs(tokens = 28_864),
            inputs(tokens = 29_193, settled = false),
            inputs(tokens = 29_193),
            inputs(conversation = CONV_B, tokens = null),
        ).contextUsageStates().toList()

        assertEquals(listOf(null, 28_864, 29_193, null), states.map { it.usage?.usedTokens })
    }

    @Test
    fun windowComesFromTheAgentLimitThenItsConfigThenTheCatalog() {
        val models = listOf(LlmModel(id = "m", handle = "lmstudio/MiniMax-M3", contextWindow = 1_000_000))
        val agent = Agent(id = AgentId(AGENT), name = "a", model = "lmstudio/MiniMax-M3")

        assertEquals(128_000, contextWindowTokensOf(agent.copy(contextWindowLimit = 128_000), models))
        assertEquals(64_000, contextWindowTokensOf(agent.copy(llmConfig = LlmConfig(contextWindow = 64_000)), models))
        assertEquals(1_000_000, contextWindowTokensOf(agent, models))
        assertNull(contextWindowTokensOf(agent, emptyList()))
        assertNull(contextWindowTokensOf(null, models))
    }

    @Test
    fun aConversationModelSwitchUsesThatModelsWindow() {
        val models = listOf(LlmModel(id = "big", handle = "openai/big", contextWindow = 400_000))
        val agent = Agent(id = AgentId(AGENT), name = "a", contextWindowLimit = 128_000)

        assertEquals(400_000, contextWindowTokensOf(agent, models, modelOverride = "openai/big"))
        assertNull(contextWindowTokensOf(agent, models, modelOverride = "openai/unknown"))
    }

    @Test
    fun theChipForAnAgentsDefaultConversationUpdatesFromABareDefaultFrame() = runTest {
        val readings = ContextTokenReadings()
        readings.record(
            com.letta.mobile.data.transport.ServerFrame.UsageStatistics(
                agentId = AGENT,
                conversationId = "default",
                contextTokens = 28_864,
            ),
        )
        val chipConversation = "conv-default-$AGENT"

        val shown = ContextReadingDisplay().advance(
            inputs(conversation = chipConversation, tokens = readings.readings.value.readingFor(AGENT, chipConversation)),
        )
        val other = ContextReadingDisplay().advance(
            inputs(conversation = CONV_A, tokens = readings.readings.value.readingFor(AGENT, CONV_A)),
        )

        assertEquals(28_864, shown.state.usage?.usedTokens)
        assertNull(other.state.usage)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"
    }
}
