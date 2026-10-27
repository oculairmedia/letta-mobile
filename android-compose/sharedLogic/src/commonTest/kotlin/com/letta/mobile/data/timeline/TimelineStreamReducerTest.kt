package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.letta.mobile.util.textOrNull
import com.letta.mobile.data.model.UserMessage


class TimelineStreamReducerTest : TimelineStreamReducerFixture() {
    @Test
    fun `ws approval request maps through timeline with approval request id`() {
        val mapped = WsFrameMapper.toLettaMessage(
            ServerFrame.ToolCallMessage(
                type = "approval_request_message",
                id = "approval-1",
                ts = "2026-05-23T00:00:00Z",
                agentId = "agent-1",
                conversationId = "conv-1",
                turnId = "turn-1",
                runId = "run-1",
                toolCall = ToolCallPayload(toolCallId = "call-approval", name = "danger", arguments = "{}"),
            )
        )!!

        val event = mapped.toTimelineEvent(position = 1.0)!!

        event.serverId shouldBe "approval-1"
        event.approvalRequestId shouldBe "approval-1"
        event.toolCalls.single().effectiveId shouldBe "call-approval"
    }

    @Test
    fun `tool return attaches to matching tool call and emits notification`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCalls = listOf(
                    ToolCall(toolCallId = "call-a", name = "read", arguments = "a"),
                    ToolCall(toolCallId = "call-b", name = "write", arguments = "b"),
                ),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-b",
            toolCallId = "call-b",
            status = "success",
            toolReturnRaw = JsonPrimitive("done"),
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.toolReturnContentByCallId["call-b"] shouldBe "done"
        event.toolReturnIsErrorByCallId["call-b"] shouldBe false
        event.approvalDecided shouldBe true
        output.updatedPendingToolReturnsByCallId shouldBe emptyMap()
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("tool-batch", toolReturn.messageType)
        )
        output.notification shouldBe PendingIngestNotification(
            serverId = "tool-batch",
            messageType = "tool_return_message",
            contentPreview = "done",
        )
    }

    @Test
    fun `replayed tool call with a different run id does not append a second row`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "toolcall-edit-1",
                runId = "run-real",
                toolCalls = listOf(ToolCall(toolCallId = "call-edit-1", name = "Edit", arguments = "{\"path\":\"a\"}")),
            ),
        ).next
        val replay = reduce(
            prev = seeded,
            frame = ToolCallMessage(
                id = "letta-msg-replay-9",
                runId = "desktop-stream-run-conv",
                toolCalls = listOf(ToolCall(toolCallId = "call-edit-1", name = "Edit", arguments = "{\"path\":\"a\"}")),
            ),
        )

        replay.next.events.size shouldBe 1
        (replay.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "toolcall-edit-1"
    }

    @Test
    fun `tool return image attaches to matching tool call attachments`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-batch",
                toolCall = ToolCall(toolCallId = "call-image", name = "Read", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-image",
            toolCallId = "call-image",
            status = "success",
            toolReturnRaw = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("letta"))
                        put("file_id", JsonPrimitive("file-tool"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("STREAM_TOOL_IMAGE+/=="))
                    })
                })
            },
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.attachments shouldBe listOf(
            MessageContentPart.Image(base64 = "STREAM_TOOL_IMAGE+/==", mediaType = "image/png")
        )
    }

    @Test
    fun `generate image tool return attaches inline image to matching tool call`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-generate-image",
                toolCall = ToolCall(toolCallId = "call-generate-image", name = "generate_image", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-generate-image",
            toolCallId = "call-generate-image",
            status = "success",
            toolReturnRaw = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("""
                        {
                          "path": "/tmp/generated-image.png",
                          "mime_type": "image/png",
                          "model": "gpt-image-2-medium",
                          "size": "1024x1024",
                          "quality": "medium",
                          "prompt": "a small brass robot"
                        }
                    """.trimIndent()))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("base64"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("STREAM_GENERATED_IMAGE+/=="))
                    })
                })
            },
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.toolReturnContentByCallId["call-generate-image"].orEmpty().contains("gpt-image-2-medium") shouldBe true
        event.attachments shouldBe listOf(
            MessageContentPart.Image(base64 = "STREAM_GENERATED_IMAGE+/==", mediaType = "image/png")
        )
    }

    @Test
    fun `tool return without matching call is buffered without rendering`() {
        val toolReturn = ToolReturnMessage(
            id = "return-first",
            toolCallId = "call-late",
            status = "success",
            toolReturnRaw = JsonPrimitive("late result"),
        )

        val output = reduce(frame = toolReturn)

        output.next.events shouldBe emptyList()
        output.updatedPendingToolReturnsByCallId shouldBe mapOf("call-late" to toolReturn)
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `blank tool return id does not attach to blank synthetic tool call`() {
        val seeded = reduce(
            frame = ToolCallMessage(
                id = "tool-blank",
                toolCall = ToolCall(name = "synthetic_tool", arguments = "{}"),
            )
        ).next
        val toolReturn = ToolReturnMessage(
            id = "return-blank",
            toolCallId = "",
            status = "error",
            toolReturnRaw = JsonPrimitive("should_not_attach"),
            isErr = true,
        )

        val output = reduce(prev = seeded, frame = toolReturn)

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.approvalDecided shouldBe false
        event.toolReturnContentByCallId shouldBe emptyMap()
        output.updatedPendingToolReturnsByCallId shouldBe emptyMap()
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }
}






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
            ).copy(logicalMessageId = "post-tool-otid"),
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


class TimelineStreamReconciliationTest : TimelineStreamReducerFixture() {
    @Test
    fun `seqId dedup skips already-ingested stream frame`() {
        val seeded = seedAssistant(textAssistant(id = "assistant-1", content = "Hello", seqId = 3))

        val output = reduce(
            prev = seeded,
            frame = textAssistant(id = "assistant-1", content = "Hel", seqId = 2),
        )
        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hello"
        output.emittedEvents shouldBe emptyList()
    }

    @Test
    fun `otid match dedupes duplicate stream frame`() {
        val seeded = reduce(
            frame = UserMessage(id = "server-user-1", contentRaw = JsonPrimitive("hello"), otid = "shared-otid")
        ).next

        val output = reduce(
            prev = seeded,
            frame = UserMessage(id = "server-user-2", contentRaw = JsonPrimitive("hello"), otid = "shared-otid"),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "server-user-1"
        assertNoStreamSideEffects(output)
    }

    @Test
    fun `hydration drops persisted synthetic skill doc envelope`() {
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                UserMessage(id = "user-question", contentRaw = JsonPrimitive("What is the router status?")),
                UserMessage(id = "skill-envelope", contentRaw = JsonPrimitive(realCapturedSkillEnvelope())),
                textAssistant(id = "assistant-answer", content = "The router is online."),
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        )

        result.timeline.events.map { (it as TimelineEvent.Confirmed).serverId } shouldBe listOf("user-question", "assistant-answer")
        result.visibleEventCount shouldBe 2
    }

    @Test
    fun `hydration drops assistant skill content payload`() {
        val skillContent = """
            <skill_content name="kotzilla-mcp-console">
            ---
            name: kotzilla-mcp-console
            description: Query performance telemetry from the console.
            ---
            ${"Read-only operational instructions. ".repeat(20)}
            </skill_content>
        """.trimIndent()
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                UserMessage(id = "question", contentRaw = JsonPrimitive("Check performance")),
                textAssistant(id = "skill-content", content = skillContent),
                textAssistant(id = "answer", content = "Performance is healthy."),
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        )

        result.timeline.events.map { (it as TimelineEvent.Confirmed).serverId } shouldBe listOf("question", "answer")
        result.visibleEventCount shouldBe 2
    }

    @Test
    fun `live stream drops user shaped skill content before canonical ingest`() {
        val skillContent = """
            <skill_content name="asus-router">
            ---
            name: asus-router
            description: Query router status.
            ---
            ${"Internal skill instructions. ".repeat(20)}
            </skill_content>
        """.trimIndent()

        val output = reduce(
            frame = UserMessage(id = "skill-content-live", contentRaw = JsonPrimitive(skillContent)),
        )

        output.next.events shouldBe emptyList()
        assertNoStreamSideEffects(output)
    }

    @Test
    fun `semantic match dedupes hydrate then ws assistant with different server id`() {
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                textAssistant(
                    id = "rest-assistant",
                    content = "Let me check the more recent one then.",
                    runId = "run-reopen",
                    seqId = 1,
                )
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        ).timeline
        Telemetry.clear()
        Telemetry.chatHotPathDebugEnabled.set(true)

        val output = reduce(
            prev = hydrated,
            frame = textAssistant(
                id = "ws-assistant",
                content = "Let me check the more recent one then.",
                runId = "run-reopen",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "rest-assistant"
        output.emittedEvents shouldBe emptyList()
        Telemetry.snapshot().any {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.eventDeduped" &&
            it.attrs["reason"]?.textOrNull == "semanticIdentitySeen"
        } shouldBe true
    }

    @Test
    fun `reconciled final with real run id replaces iroh synthetic live row`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-300",
                content = "Hello",
                runId = "iroh-run-client-synthetic",
                seqId = 1,
            ))

        val output = reduce(
            prev = live,
            frame = textAssistant(
                id = "letta-msg-300",
                content = "Hello!",
                runId = "server-run-real",
                seqId = 2,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "letta-msg-300"
        event.runId shouldBe "server-run-real"
        event.content shouldBe "Hello!"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("letta-msg-300", "assistant_message")
        )
    }

    @Test
    fun `synthetic live to real final uses incoming snapshot on content conflict`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-302",
                content = "synthetic partial that should not survive",
                runId = "iroh-run-client-synthetic",
                seqId = null,
            ))

        val output = reduce(
            prev = live,
            frame = textAssistant(
                id = "letta-msg-302",
                content = "final",
                runId = "server-run-real",
                seqId = null,
            ),
        )

        val event = singleConfirmed(output)
        event.runId shouldBe "server-run-real"
        event.content shouldBe "final"
    }

    @Test
    fun `synthetic duplicate seq still reconciles real run id`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-303",
                content = "Hello!",
                runId = "iroh-run-client-synthetic",
                seqId = 2,
            ))

        val output = reduce(
            prev = live,
            frame = textAssistant(
                id = "letta-msg-303",
                content = "Hello!",
                runId = "server-run-real",
                seqId = 2,
            ),
        )

        val event = singleConfirmed(output)
        event.runId shouldBe "server-run-real"
        event.content shouldBe "Hello!"
    }

    @Test
    fun `mid-stream run id promotion keeps appended tokens instead of dropping them`() {
        // P3 canonical ids: the transport promotes a synthetic iroh-run row to
        // the real server run id mid-stream. A promoted frame that also carries a
        // strictly-higher seq id is a genuine forward stream delta, NOT the
        // message.list reconcile snapshot the synthetic->real path was written
        // for. It must merge as a forward delta (append) rather than a snapshot
        // keep-longer, which would drop the earlier tokens.
        val live = seedAssistant(textAssistant(
                id = "letta-msg-700",
                content = "Hello",
                runId = "iroh-run-client-synthetic",
                seqId = 1,
            ))

        val output = reduce(
            prev = live,
            frame = textAssistant(
                id = "letta-msg-700",
                content = " world",
                runId = "run-real-app-server",
                seqId = 2,
            ),
        )

        val event = singleConfirmed(output)
        event.serverId shouldBe "letta-msg-700"
        // Merge decided by serverId + run id (single row), not otid/semantic dedup.
        event.runId shouldBe "run-real-app-server"
        event.content shouldBe "Hello world"
    }

    @Test
    fun `reconcile snapshot without seq id still replaces synthetic live row longer text`() {
        // The message.list reconcile final carries no seq id, so it must stay on
        // the snapshot-replacement path (keep the complete final) and NOT be
        // treated as a forward delta appended to the partial.
        val live = seedAssistant(textAssistant(
                id = "letta-msg-701",
                content = "Hel",
                runId = "iroh-run-client-synthetic",
                seqId = 5,
            ))

        val output = reduce(
            prev = live,
            frame = textAssistant(
                id = "letta-msg-701",
                content = "Hello, world",
                runId = "run-real-app-server",
                seqId = null,
            ),
        )

        val event = singleConfirmed(output)
        event.runId shouldBe "run-real-app-server"
        event.content shouldBe "Hello, world"
    }

    @Test
    fun `reconcile copy without run id does not duplicate live row with real run id`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-166",
                content = "Hey",
                runId = "run-real-app-server",
                seqId = 3,
            ))

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                textAssistant(
                    id = "letta-msg-166",
                    content = "Hey",
                    runId = null,
                    seqId = null,
                )
            )
        )

        changed shouldBe 0
        mergedTimeline.events shouldHaveSize 1
        val event = mergedTimeline.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Hey"
        event.runId shouldBe "run-real-app-server"
    }

    @Test
    fun `ui-msg final without exact alias preserves identical live row`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-166",
                content = "Hey",
                runId = "run-real-app-server",
                seqId = 3,
            ))

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                textAssistant(
                    id = "ui-msg-55781",
                    content = "Hey",
                    runId = null,
                    seqId = null,
                )
            )
        )

        changed shouldBe 1
        mergedTimeline.events shouldHaveSize 2
        (mergedTimeline.events.first() as TimelineEvent.Confirmed).serverId shouldBe "letta-msg-166"
    }

    @Test
    fun `recent reconcile final assistant replaces live prefix row`() {
        val live = seedAssistant(textAssistant(
                id = "letta-msg-624",
                content = "Let",
                runId = "local-run-15",
                seqId = 1,
            ))

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                textAssistant(
                    id = "letta-msg-640",
                    content = "Let's find out — no duplicate here.",
                    runId = null,
                    seqId = null,
                )
            )
        )

        changed shouldBe 1
        mergedTimeline.events shouldHaveSize 1
        val event = mergedTimeline.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "letta-msg-640"
        event.content shouldBe "Let's find out — no duplicate here."
        event.position shouldBe (live.events.single() as TimelineEvent.Confirmed).position
    }

    @Test
    fun `recent reconcile real run replaces iroh synthetic live row`() {
        val live = seedAssistant(textAssistant(
            id = "letta-msg-301",
            content = "Hello",
            runId = "iroh-run-client-synthetic",
            seqId = 1,
        ).copy(
            logicalMessageId = "lm-301",
        ))

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                textAssistant(
                    id = "letta-msg-301",
                    content = "Hello!",
                    runId = "server-run-real",
                    seqId = 2,
                )
            )
        )

        changed shouldBe 1
        mergedTimeline.events shouldHaveSize 1
        val event = mergedTimeline.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "letta-msg-301"
        event.runId shouldBe "server-run-real"
        event.content shouldBe "Hello!"
    }

    @Test
    fun `iroh reconciled final collapses into synthetic-otid streamed draft even when not a clean prefix`() {
        // letta-mobile-x1xnl SECOND path. The live Iroh stream lands the
        // assistant reply as a draft row keyed on the turn-anchored SYNTHETIC
        // otid (iroh-assistant-<turnId>) minted by IrohStreamDeltaServerFrameMapper
        // because the wire frames carry no otid. A moment later the SAME reply
        // arrives again via the message.list reconcile snapshot — this time with
        // a REAL server otid/id (a different letta-msg-* id than the last rotating
        // streamed fragment) and the FULL text. The on-device symptom is the first
        // word lagging on the draft ("Still" strands) so the draft text is NOT a
        // clean prefix of the reconciled full text — defeating the
        // startsWith-based prefix replace. Both rows share the SAME real run id, so
        // the reconcile must collapse the snapshot into the draft row instead of
        // appending a second, near-identical row.
        val streamedDraft = seedAssistant(textAssistant(
            // Last rotating backend id observed on the live stream.
                id = "letta-msg-5021",
            // Draft is missing the leading word ("Still") — first-word lag.
                content = " kicking. Did anything else break?",
            runId = "run-real-app-server",
            seqId = 7,
        ).copy(
            otid = "iroh-assistant-turn-42",
        ))

        streamedDraft.events shouldHaveSize 1

        val (mergedTimeline, changed) = streamedDraft.mergeServerMessages(
            listOf(
                textAssistant(
                    // Reconcile mints its OWN id, distinct from the streamed one.
                    id = "letta-msg-final-99",
                    content = "Still kicking. Did anything else break?",
                    runId = "run-real-app-server",
                    seqId = null,
                ).copy(
                    otid = null,
                )
            )
        )

        changed shouldBe 1
        mergedTimeline.events shouldHaveSize 1
        val event = mergedTimeline.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Still kicking. Did anything else break?"
        event.serverId shouldBe "letta-msg-final-99"
        event.position shouldBe (streamedDraft.events.single() as TimelineEvent.Confirmed).position
    }

    @Test
    fun `reused assistant server id from a different run appends live event`() {
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                textAssistant(
                    id = "letta-msg-203",
                    content = "old answer from a prior app server run",
                    runId = "local-run-old",
                    seqId = 146,
                )
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        ).timeline

        val output = reduce(
            prev = hydrated,
            frame = textAssistant(
                id = "letta-msg-203",
                content = "hello from iro",
                runId = "local-run-new",
                seqId = 1,
            ).copy(
                logicalMessageId = "lm-203-new",
            ),
        )

        output.next.events shouldHaveSize 2
        val live = output.next.events.last() as TimelineEvent.Confirmed
        live.serverId shouldBe "letta-msg-203"
        live.runId shouldBe "local-run-new"
        live.content shouldBe "hello from iro"
        output.next.liveCursor shouldBe "letta-msg-203"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("letta-msg-203", "assistant_message")
        )
    }
}


abstract class TimelineStreamReducerFixture : com.letta.mobile.util.TelemetryRecordingTest() {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.chatHotPathDebugEnabled.set(false)
    }

    protected fun textAssistant(
        id: String,
        content: String,
        runId: String? = null,
        seqId: Int? = null,
    ): AssistantMessage = AssistantMessage(
        id = id, contentRaw = JsonPrimitive(content), runId = runId,
        seqId = seqId,
    )

    protected fun singleConfirmed(output: TimelineReducerOutput): TimelineEvent.Confirmed {
        output.next.events shouldHaveSize 1
        return output.next.events.single() as TimelineEvent.Confirmed
    }

    protected fun assertNoStreamSideEffects(output: TimelineReducerOutput) {
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    protected fun seedAssistant(frame: AssistantMessage): Timeline = reduce(frame = frame).next

    protected fun reduce(
        prev: Timeline = timeline(),
        frame: com.letta.mobile.data.model.LettaMessage,
        pendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage> = persistentMapOf(),
        agentId: String? = null,
    ): TimelineReducerOutput = reduceStreamFrame(
        TimelineReducerInput(
            prev = prev,
            frame = frame,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
            agentId = agentId,
        )
    )

    protected fun timeline(): Timeline = Timeline(conversationId = "conv-test")

    protected fun telemetryNamesForMerge(existing: String, incoming: String): Set<String> {
        Telemetry.clear()
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-telemetry",
                contentRaw = JsonPrimitive(existing),
                seqId = 1,
            )
        ).next
        reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-telemetry",
                contentRaw = JsonPrimitive(incoming),
                seqId = 2,
            ),
        )
        return Telemetry.snapshot().map { it.name }.toSet()
    }

    protected fun realCapturedSkillEnvelope(): String = """
        <asus-router>
        name: asus-router
        description: Pull stats from ASUS RT-AX82U router and summarize WAN/LAN status.
        ---
        # ASUS Router

        This skill connects to the ASUS router API and reports useful status.

        ## Usage

        Ask for router status, WAN uptime, connected clients, or traffic counters.

        ```json
        { "action": "status" }
        ```

        ## Notes

        The router credentials are configured by the host environment.

        ARGUMENTS: status
        </asus-router>
    """.trimIndent()

}


internal infix fun <T> T.shouldBe(expected: T) {
    assertEquals(expected, this)
}

internal infix fun Collection<*>.shouldHaveSize(expected: Int) {
    assertEquals(expected, size)
}
