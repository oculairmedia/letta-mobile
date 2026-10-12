package com.letta.mobile.ui.context

import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.ContextMeter
import com.letta.mobile.data.context.ConversationModelView
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import com.letta.mobile.data.repository.modelcontrol.ConversationModelSelections
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-dxz5s: the drawer card for a conversation whose model was changed outside the app.
 *
 * Each case starts from a fresh [ConversationModelSelections] with no `record()`: the cold start
 * after the override was set on the host (CLI / desktop). Only the host's conversation record says
 * which model the conversation runs, so a pass here cannot come from an in-session switch.
 */
class AgentContextCardConversationOverrideTest {
    private val agent = Agent(id = AgentId("agent-c356b54a"), name = "PM-letta-mobile", model = AGENT_MODEL, contextWindowLimit = 128_000)
    private val models = listOf(
        LlmModel(id = AGENT_MODEL, handle = AGENT_MODEL, contextWindow = 128_000),
        LlmModel(id = OPUS, handle = OPUS, contextWindow = 1_000_000),
    )

    @Test
    fun theCardShowsTheConversationsOverrideAndItsWindowAfterAColdStart() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(hostRecord()))

        val view = ConversationModelView.of(selections.choices.value, CONVERSATION, agent, models)
        val card = card(view)

        assertEquals(OPUS, view.model)
        assertEquals(OPUS, card.modelLabel)
        assertEquals(1_000_000, view.windowTokens)
        assertEquals("20% of 1M used", usageLine(card))
    }

    @Test
    fun withoutTheHostRecordTheCardFellBackToTheAgentAndInflatedItsWindow() {
        // The defect as reported: the agent's 128k window is smaller than the 201.9k reading, and
        // the meter lifts its window to the total, so it read "100% of 201.9k used".
        val view = ConversationModelView.of(ConversationModelChoices(), CONVERSATION, agent, models)

        assertEquals(AGENT_MODEL, view.model)
        assertEquals("100% of 201.9k used", usageLine(card(view)))
    }

    @Test
    fun aConversationWithNoOverrideStillShowsTheAgentsModel() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(json.decodeFromString(Conversation.serializer(), """{"id":"$CONVERSATION","agent_id":"agent-c356b54a"}""")))

        val view = ConversationModelView.of(selections.choices.value, CONVERSATION, agent, models)

        assertEquals(AGENT_MODEL, view.model)
        assertEquals(128_000, view.windowTokens)
    }

    private fun card(view: ConversationModelView): AgentContextCardModel = AgentContextCardModel.present(
        AgentContextCardInputs(
            modelLabel = view.label,
            effort = view.effort,
            meter = ContextMeter.of(READING, view.windowTokens),
            conversationIsDefault = false,
            compactSupported = null,
            compacting = false,
            turnRunning = false,
        ),
    )

    private fun hostRecord(): Conversation = json.decodeFromString(
        Conversation.serializer(),
        """{"id":"$CONVERSATION","agent_id":"agent-c356b54a","model":"$OPUS","context_window_limit":1000000,
           "model_settings":{"provider_type":"anthropic","context_window_limit":1000000,"max_tokens":128000,
           "effort":"low","thinking":{"type":"enabled","budget_tokens":10000},"reasoning_effort":"low"}}""",
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val CONVERSATION = "local-conv-517"
        const val OPUS = "anthropic/claude-opus-5-5"
        const val AGENT_MODEL = "lmstudio/gpt-6-sol"
        const val READING = 201_900
    }
}
