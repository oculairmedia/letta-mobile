package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserverStreamPromotionPolicyTest {
    @Test
    fun `provisional observer assistant promotes to canonical identity off tail le5m6`() {
        var timeline = reduce(AssistantMessage("observer-assistant", JsonPrimitive("Su"), runId = "iroh-observer-run-77", otid = "observer-otid", seqId = 0))
        val stableServerId = (timeline.events.single() as TimelineEvent.Confirmed).serverId
        timeline = reduce(ReasoningMessage("reasoning-between-copies", "Checking the request", runId = "run-real-77", otid = "reasoning-otid", seqId = 1), timeline)
        timeline = reduce(AssistantMessage("canonical-assistant", JsonPrimitive("Sure, here it is."), runId = "run-real-77", otid = "canonical-otid", seqId = 2), timeline)

        val assistants = timeline.assistants()
        assertEquals(1, assistants.size, "observer and initiator copies must project as one assistant row")
        assertEquals("Sure, here it is.", assistants.single().content)
        assertEquals(stableServerId, assistants.single().serverId)
    }

    @Test
    fun `observer promotion ignores unrelated synthetic and settled candidates le5m6`() {
        var timeline = reduce(AssistantMessage("settled-observer", JsonPrimitive("Su"), runId = "iroh-observer-run-settled", seqId = 0))
        timeline = reduce(UserMessage("new-turn", JsonPrimitive("Start another turn")), timeline)
        timeline = reduce(AssistantMessage("unrelated-observer", JsonPrimitive("Su"), runId = "iroh-observer-run-unrelated", seqId = 0), timeline)
        timeline = reduce(ReasoningMessage("other-run-reasoning", "Different run", runId = "run-other", seqId = 1), timeline)
        timeline = reduce(AssistantMessage("canonical", JsonPrimitive("Sure, here it is."), runId = "run-canonical", seqId = 2), timeline)

        assertEquals(listOf("settled-observer", "unrelated-observer", "canonical"), timeline.assistants().map { it.serverId })
    }

    @Test
    fun `observer promotion chooses nearest eligible candidate le5m6`() {
        var timeline = Timeline(
            conversationId = "conv-test",
            events = persistentListOf(
                TimelineEvent.Confirmed(
                    position = 0.0,
                    otid = "older-observer-otid",
                    content = "S",
                    serverId = "older-observer",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-older",
                    stepId = null,
                    seqId = 0,
                ),
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "nearest-observer-otid",
                    content = "Sur",
                    serverId = "nearest-observer",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "iroh-observer-run-nearest",
                    stepId = null,
                    seqId = 1,
                ),
            ),
        )
        timeline = reduce(ReasoningMessage("run-bridge", "Checking", runId = "run-real-nearest", seqId = 2), timeline)
        timeline = reduce(AssistantMessage("canonical-nearest", JsonPrimitive("Sure, done."), runId = "run-real-nearest", seqId = 3), timeline)

        val assistants = timeline.assistants()
        assertEquals(listOf("older-observer", "nearest-observer"), assistants.map { it.serverId })
        assertEquals("Sure, done.", assistants.last().content)
        assertEquals("run-real-nearest", assistants.last().runId)
        assertEquals(3, assistants.last().seqId)
    }

    @Test
    fun `canonical content replaces whitespace-padded observer prefix le5m6`() {
        var timeline = reduce(AssistantMessage("observer-assistant", JsonPrimitive("Hello     "), runId = "iroh-observer-run-spaces", seqId = 0))
        timeline = reduce(ReasoningMessage("run-bridge", "Checking", runId = "run-real-spaces", seqId = 1), timeline)
        timeline = reduce(AssistantMessage("canonical-assistant", JsonPrimitive("Hello!"), runId = "run-real-spaces", seqId = 2), timeline)

        assertEquals(listOf("Hello!"), timeline.assistants().map { it.content })
    }

    @Test
    fun `prefix text in distinct turns remains separate without provenance match le5m6`() {
        var timeline = reduce(AssistantMessage("earlier-assistant", JsonPrimitive("Su"), runId = null, otid = "earlier-turn-otid", seqId = 0))
        timeline = reduce(AssistantMessage("later-assistant", JsonPrimitive("Sure, here it is."), runId = "run-later-turn", otid = "later-turn-otid", seqId = 0), timeline)

        assertEquals(listOf("Su", "Sure, here it is."), timeline.assistants().map { it.content })
    }

    @Test
    fun `blank run prefix remains separate from later canonical stream le5m6`() {
        var timeline = reduce(AssistantMessage("blank-run-assistant", JsonPrimitive("Su"), runId = null, seqId = 0))
        timeline = reduce(ReasoningMessage("run-bridge", "Checking", runId = "run-real-blank", seqId = 1), timeline)
        timeline = reduce(AssistantMessage("canonical-assistant", JsonPrimitive("Sure, here it is."), runId = "run-real-blank", seqId = 2), timeline)

        assertEquals(listOf("blank-run-assistant", "canonical-assistant"), timeline.assistants().map { it.serverId })
    }

    private fun reduce(frame: LettaMessage, previous: Timeline = Timeline("conv-test")): Timeline =
        reduceStreamFrame(TimelineReducerInput(previous, frame, persistentMapOf())).next

    private fun Timeline.assistants(): List<TimelineEvent.Confirmed> = events
        .filterIsInstance<TimelineEvent.Confirmed>()
        .filter { it.messageType == TimelineMessageType.ASSISTANT }
}
