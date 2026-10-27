package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.util.Telemetry
import kotlin.test.Test


class TimelineStreamCleanupTest : TimelineStreamReducerFixture() {
    @Test
    fun `stream reducer hot path telemetry is disabled by default`() {
        Telemetry.chatHotPathDebugEnabled.set(false)
        Telemetry.clear()

        reduce(frame = textAssistant(id = "assistant-default", content = "hello"))

        Telemetry.snapshot().none {
            it.tag == "TimelineSync" && it.name.startsWith("streamSubscriber")
        } shouldBe true
    }

    @Test
    fun `stream reducer hot path telemetry can be enabled deliberately`() {
        Telemetry.chatHotPathDebugEnabled.set(true)
        Telemetry.clear()

        reduce(frame = textAssistant(id = "assistant-debug", content = "hello"))

        Telemetry.snapshot().any {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.ingested" &&
                it.level == Telemetry.Level.DEBUG
        } shouldBe true
    }

    @Test
    fun `late same-run assistant prefix with a new server id is dropped`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "Opening line\n\nFinal body",
                runId = "run-1",
                seqId = 20,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-prefix",
                content = "Opening line",
                runId = "run-1",
                seqId = 21,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "Opening line\n\nFinal body"
        output.next.liveCursor shouldBe "assistant-full"
        assertNoStreamSideEffects(output)
    }

    @Test
    fun `late same-run blank assistant with a new server id is dropped after nonblank assistant`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "Opening line\n\nFinal body",
                runId = "run-1",
                seqId = 20,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-empty",
                content = "",
                runId = "run-1",
                seqId = 21,
            ),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "assistant-full"
        assertNoStreamSideEffects(output)
    }

    @Test
    fun `post install replay prefix with seq one is dropped`() {
        val seeded = seedAssistant(textAssistant(
                id = "rest-assistant",
                content = "Got it — I can check the latest build and keep going.",
                runId = "run-replayed-after-install",
                seqId = 24,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "ws-replayed-prefix",
                content = "Got",
                runId = "run-replayed-after-install",
                seqId = 1,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "rest-assistant"
        event.content shouldBe "Got it — I can check the latest build and keep going."
        assertNoStreamSideEffects(output)
    }

    @Test
    fun `assistant prefix from a different run is preserved`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "Opening line\n\nFinal body",
                runId = "run-1",
                seqId = 20,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-prefix-next-run",
                content = "Opening line",
                runId = "run-2",
                seqId = 1,
            ),
        )

        output.next.events shouldHaveSize 2
        val event = output.next.events.last() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-prefix-next-run"
        event.content shouldBe "Opening line"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-prefix-next-run", "assistant_message")
        )
    }

    @Test
    fun `plain append adds new confirmed event and emits notification for assistant`() {
        val output = reduce(
            frame = textAssistant(id = "assistant-1", content = "hello")
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-1"
        event.content shouldBe "hello"
        output.next.liveCursor shouldBe "assistant-1"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-1", "assistant_message")
        )
        output.notification shouldBe PendingIngestNotification(
            serverId = "assistant-1",
            messageType = "assistant_message",
            contentPreview = "hello",
        )
    }

    @Test
    fun `ujz3x first content delta after tool_return not dropped as prefix orphan`() {
        // Setup: a previous assistant message in the same run starts with "Yes — the"
        val seeded = seedAssistant(textAssistant(
                id = "assistant-before-tool",
                content = "Yes — the `Agent` tool takes an optional `model` parameter",
                runId = "run-1",
                seqId = 1,
            ))

        // Now the post-tool-return content stream starts. The first delta is just "Y"
        // — a single character that coincidentally is a prefix of the previous
        // assistant message. This must NOT be dropped.
        val output1 = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "cm-stream-post-tool-otid",
                content = "Y",
                runId = "run-1",
                seqId = 1,  // FIRST frame for this new otid — genuinely new message
                logicalMessageId = "post-tool-otid",
            ),
        )

        output1.next.events shouldHaveSize 2
        val event1 = output1.next.events.last() as TimelineEvent.Confirmed
        event1.serverId shouldBe "cm-stream-post-tool-otid"
        event1.content shouldBe "Y"
        event1.otid shouldBe "post-tool-otid"

        // Second delta continues: "es — confirmed working at both layers:"
        val output2 = reduce(
            prev = output1.next,
            frame = textAssistant(
                id = "cm-stream-post-tool-otid",
                content = "es — confirmed working at both layers:",
                runId = "run-1",
                seqId = 2,
            ).copy(
                logicalMessageId = "post-tool-otid",
            ),
        )

        output2.next.events shouldHaveSize 2
        val event2 = output2.next.events.last() as TimelineEvent.Confirmed
        event2.serverId shouldBe "cm-stream-post-tool-otid"
        event2.content shouldBe "Yes — confirmed working at both layers:"
        // The first token 'Y' must be preserved — the full content starts with "Yes"
        event2.content.startsWith("Yes") shouldBe true
    }

    @Test
    fun `assistant one character prefix replay is dropped after reconciled final without run id`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-final",
                content = "Nah, that was just the bit. Real answer: agents stay off the relay.",
                runId = null,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-prefix",
                content = "N",
                runId = "local-run-39",
                seqId = 1,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "assistant-final"
        event.content shouldBe "Nah, that was just the bit. Real answer: agents stay off the relay."
        output.emittedEvents shouldBe emptyList()
    }

    @Test
    fun `assistant prefix replay from local run is dropped after iroh final`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "Yeah — still seeing it. Smells like a dedup issue.",
                runId = "iroh-run-123",
                seqId = 12,
            ))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-prefix",
                content = "Yeah",
                runId = "local-run-12",
                seqId = 1,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "Yeah — still seeing it. Smells like a dedup issue."
        output.emittedEvents shouldBe emptyList()
    }



    @Test
    fun `failed turn with sole short assistant fragment removes tail row`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-fragment",
                content = "No",
                runId = "run-failed",
                seqId = 1,
            ))

        val cleaned = seeded.cleanupAbandonedAssistantFragments(
            runId = "run-failed",
            turnId = "turn-failed",
            reason = "turn_done_failed",
        ).timeline

        cleaned.events shouldHaveSize 0
        cleaned.residentOtids shouldHaveSize 0
        cleaned.invariantsKnown shouldBe true
    }


    @Test
    fun `cleanup uses observed real run id when terminal frame carries synthetic run id`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-fragment",
                content = "Ok",
                runId = "run-real-observed",
                seqId = 1,
            ))

        val cleaned = seeded.cleanupAbandonedAssistantFragments(
            runId = "iroh-run-terminal",
            turnId = "turn-terminal",
            reason = "turn_done_failed",
            candidateRunIds = setOf("run-real-observed"),
        ).timeline

        cleaned.events shouldHaveSize 0
    }

    @Test
    fun `tail-only cleanup preserves earlier legitimate short assistant before tool event`() {
        val shortAssistant = seedAssistant(textAssistant(
                id = "assistant-ok",
                content = "OK",
                runId = "run-legit",
                seqId = 1,
            ))
        val withTool = reduce(
            prev = shortAssistant,
            frame = ToolCallMessage(
                id = "tool-call",
                runId = "run-legit",
                toolCall = ToolCall(toolCallId = "call-1", name = "lookup", arguments = "{}"),
            ),
        ).next

        val cleaned = withTool.cleanupAbandonedAssistantFragments(
            runId = "run-legit",
            turnId = "turn-legit",
            reason = "turn_done_failed",
        ).timeline

        cleaned.events shouldHaveSize 2
        (cleaned.events.first() as TimelineEvent.Confirmed).content shouldBe "OK"
    }

    @Test
    fun `terminal disconnect without cleanup target preserves legitimate short tail reply`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-ok",
                content = "OK",
                runId = "run-legit",
                seqId = 1,
            ))

        val cleaned = seeded.cleanupAbandonedAssistantFragments(
            runId = null,
            turnId = null,
            reason = "disconnect",
        ).timeline

        cleaned.events shouldHaveSize 1
        (cleaned.events.single() as TimelineEvent.Confirmed).content shouldBe "OK"
    }

    @Test
    fun `terminal failed turn cleanup removes one character assistant fragment`() {
        val withFull = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "I can help clean up the lifecycle.",
                runId = "run-terminal",
                seqId = 12,
            ))
        val withFragment = withFull.append(
            (textAssistant(
                id = "assistant-orphan-i",
                content = "I",
                runId = "run-terminal",
                seqId = 13,
            ).toTimelineEvent(position = withFull.nextLocalPosition())!!),
        )

        val cleaned = withFragment.cleanupAbandonedAssistantFragments("run-terminal", "turn-terminal", "turn_done_failed").timeline

        cleaned.events shouldHaveSize 1
        val event = cleaned.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "I can help clean up the lifecycle."
    }

    @Test
    fun `terminal disconnect cleanup removes strict prefix assistant fragment`() {
        val withFull = seedAssistant(textAssistant(
                id = "assistant-full",
                content = "Nah, that was the full response.",
                runId = "run-disconnect",
                seqId = 20,
            ))
        val withFragment = withFull.append(
            textAssistant(
                id = "assistant-orphan-n",
                content = "N",
                runId = "run-disconnect",
                seqId = 21,
            ).toTimelineEvent(position = withFull.nextLocalPosition())!!,
        )

        val cleaned = withFragment.cleanupAbandonedAssistantFragments("run-disconnect", "turn-disconnect", "disconnect").timeline

        cleaned.events shouldHaveSize 1
        (cleaned.events.single() as TimelineEvent.Confirmed).serverId shouldBe "assistant-full"
    }

    @Test
    fun `terminal failed turn cleanup removes sole one character assistant fragment`() {
        val withFragment = seedAssistant(textAssistant(
                id = "assistant-orphan-i",
                content = "I",
                runId = "local-run-terminal",
                seqId = 1,
            ))

        val cleaned = withFragment.cleanupAbandonedAssistantFragments("iroh-run-terminal", "turn-terminal", "turn_done_failed").timeline

        cleaned.events shouldHaveSize 0
    }


    @Test
    fun `terminal cleanup with synthetic run id removes observed real run fragment`() {
        val withFragment = seedAssistant(textAssistant(
                id = "assistant-real-run-fragment",
                content = "I",
                runId = "run-app",
                seqId = 1,
            ))

        val cleaned = withFragment.cleanupAbandonedAssistantFragments(
            runId = "iroh-run-terminal",
            turnId = "turn-terminal",
            reason = "turn_done_failed",
            candidateRunIds = setOf("run-app"),
        ).timeline

        cleaned.events shouldHaveSize 0
    }

    @Test
    fun `abandoned fragment suppression prevents recent reconcile reinsert`() {
        val withFragment = seedAssistant(textAssistant(
                id = "assistant-orphan-reconcile",
                content = "I",
                runId = "run-reconcile",
                seqId = 1,
            ))
        val cleaned = withFragment.cleanupAbandonedAssistantFragments(
            runId = "run-reconcile",
            turnId = "turn-reconcile",
            reason = "turn_done_failed",
        ).timeline

        val (mergedTimeline, changed) = cleaned.mergeServerMessages(
            listOf(
                textAssistant(
                    id = "assistant-orphan-reconcile",
                    content = "I",
                    runId = "run-reconcile",
                    seqId = 1,
                )
            )
        )

        changed shouldBe 0
        mergedTimeline.events shouldHaveSize 0
    }

    @Test
    fun `terminal cleanup only removes tail assistant fragments`() {
        val earlier = seedAssistant(textAssistant(
                id = "assistant-ok",
                content = "OK",
                runId = "run-terminal",
                seqId = 1,
            ))
        val withToolCall = earlier.append(
            ToolCallMessage(
                id = "tool-call",
                toolCall = ToolCall(toolCallId = "call-1", name = "noop", arguments = "{}"),
                runId = "run-terminal",
            ).toTimelineEvent(position = earlier.nextLocalPosition())!!,
        )
        val cleaned = withToolCall.cleanupAbandonedAssistantFragments("run-terminal", "turn-terminal", "turn_done_failed").timeline

        cleaned.events shouldHaveSize 2
        (cleaned.events.first() as TimelineEvent.Confirmed).serverId shouldBe "assistant-ok"
    }

    @Test
    fun `successful post tool one character continuation is preserved before terminal cleanup`() {
        val seeded = seedAssistant(textAssistant(
                id = "assistant-before-tool",
                content = "Yes — earlier answer before tool.",
                runId = "run-success",
                seqId = 1,
            ))
        val withContinuation = reduce(
            prev = seeded,
            frame = textAssistant(
                id = "assistant-post-tool",
                content = "Y",
                runId = "run-success",
                seqId = 1,
            ).copy(
                otid = "post-tool",
            ),
        ).next
        val completed = reduce(
            prev = withContinuation,
            frame = textAssistant(
                id = "assistant-post-tool",
                content = "es, that worked.",
                runId = "run-success",
                seqId = 2,
            ).copy(
                otid = "post-tool",
            ),
        ).next

        completed.events shouldHaveSize 2
        val event = completed.events.last() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-post-tool"
        event.content shouldBe "Yes, that worked."
    }
}

