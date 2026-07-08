package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineStreamReducerTest {
    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.chatHotPathDebugEnabled.set(false)
    }

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
    fun `approval response marks matching approval request decided`() {
        val approvalRequest = ApprovalRequestMessage(
            id = "approval-1",
            toolCall = ToolCall(toolCallId = "call-approval", name = "danger", arguments = "{}"),
        )
        val seeded = reduce(frame = approvalRequest).next

        val output = reduce(
            prev = seeded,
            frame = ApprovalResponseMessage(
                id = "approval-response-1",
                approvalRequestId = "approval-1",
                approve = true,
            ),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.approvalRequestId shouldBe "approval-1"
        event.approvalDecided shouldBe true
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("approval-1", "approval_response_message")
        )
        output.notification shouldBe null
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

    @Test
    fun `server id match merges stream deltas into existing confirmed event`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hel"))
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("lo")),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Hello"
        output.next.liveCursor shouldBe "assistant-1"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("assistant-1", "assistant_message")
        )
        output.notification shouldBe null
    }

    @Test
    fun `iroh cumulative assistant chunks with per-chunk ids but shared otid merge into one row`() {
        // The App Server streams CUMULATIVE assistant deltas (each chunk carries
        // the full text so far). Over Iroh it mints a NEW backend letta-msg-* id
        // per chunk but keeps a STABLE otid for the message. Without the
        // otid-cumulative-merge, the fuller second chunk is dropped as an otid
        // duplicate and the UI stays stuck on "Got".
        val seeded = reduce(
            frame = AssistantMessage(
                id = "letta-msg-5020",
                contentRaw = JsonPrimitive("Got"),
                runId = "run-real-app-server",
                otid = "otid-assistant-1",
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-5021",
                contentRaw = JsonPrimitive("Got it — Iroh transport is streaming the response."),
                runId = "run-real-app-server",
                otid = "otid-assistant-1",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "letta-msg-5020"
        event.content shouldBe "Got it — Iroh transport is streaming the response."
        event.seqId shouldBe 2
        // liveCursor advances to the just-ingested chunk id so reconcile's
        // `after` cursor does not stall on the first chunk (codex review P2).
        output.next.liveCursor shouldBe "letta-msg-5021"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("letta-msg-5020", "assistant_message")
        )
    }

    @Test
    fun `seq-less same-otid cumulative chunks snapshot-merge instead of appending`() {
        // Codex review P2: when both frames lack seq ids, the old
        // canUseSnapshotMerge=false path fell through to APPEND, turning
        // "Got" + "Got it" into "GotGot it". Same-otid assistant frames are
        // cumulative by contract, so snapshot-merge must win regardless of seq.
        val seeded = reduce(
            frame = AssistantMessage(
                id = "letta-msg-6000",
                contentRaw = JsonPrimitive("Got"),
                runId = "run-app",
                otid = "otid-seqless",
                seqId = null,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-6001",
                contentRaw = JsonPrimitive("Got it working now."),
                runId = "run-app",
                otid = "otid-seqless",
                seqId = null,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Got it working now."
    }

    @Test
    fun `otid merge promotes synthetic iroh-run id to the real run id`() {
        // Codex review P2: per-chunk backend ids route through the otid path,
        // bypassing the serverId-merge run-id promotion. The merged row must
        // adopt the real run id so run-scoped grouping/collapse works.
        val seeded = reduce(
            frame = AssistantMessage(
                id = "letta-msg-7000",
                contentRaw = JsonPrimitive("I be"),
                runId = "iroh-run-synthetic-1",
                otid = "otid-runpromote",
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-7001",
                contentRaw = JsonPrimitive("I bet. Debugging heisenbugs is hell."),
                runId = "run-real-server",
                otid = "otid-runpromote",
                seqId = 2,
            ),
        )

        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "I bet. Debugging heisenbugs is hell."
        event.runId shouldBe "run-real-server"
    }

    @Test
    fun `same otid never creates a second assistant row via the append path`() {
        // Defensive one-row-per-otid invariant: if an assistant increment reaches
        // the append path while a row for its otid already exists (a fanout/timing
        // race that bypassed the earlier otid merge), it must merge into the
        // existing row rather than append a stranded duplicate. Simulate by
        // seeding a row whose serverId will NOT match the incoming frame and whose
        // otid index is present, then feeding a fuller same-otid frame with a new
        // serverId.
        val seeded = reduce(
            frame = AssistantMessage(
                id = "letta-msg-5785",
                contentRaw = JsonPrimitive("Hey, welcome back."),
                runId = "run-1",
                otid = "otid-dup",
                seqId = 10,
            )
        ).next

        // A racing increment for the SAME otid but a brand-new serverId and no
        // detectable prefix relationship — the exact shape that produced the
        // stranded ", welcome back..." row on-device.
        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-5815",
                contentRaw = JsonPrimitive("Hey, welcome back. Connection seems solid."),
                runId = "run-1",
                otid = "otid-dup",
                seqId = 11,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "letta-msg-5785"
        event.content shouldBe "Hey, welcome back. Connection seems solid."
    }

    @Test
    fun `distinct otids in the same run stay separate assistant rows`() {
        // Guard against over-merging: a tool-mediated run can legitimately have
        // multiple assistant messages. They carry DISTINCT otids, so the
        // otid-cumulative-merge must not collapse them.
        val seeded = reduce(
            frame = AssistantMessage(
                id = "letta-msg-6000",
                contentRaw = JsonPrimitive("Let me check that."),
                runId = "run-1",
                otid = "otid-a",
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-6001",
                contentRaw = JsonPrimitive("Done — here is the result."),
                runId = "run-1",
                otid = "otid-b",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 2
        val texts = output.next.events.map { (it as TimelineEvent.Confirmed).content }
        texts shouldBe listOf("Let me check that.", "Done — here is the result.")
    }

    @Test
    fun `cumulative frames do not double existing text`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hey"), seqId = 1)
        ).next

        val out2 = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hey Emmanuel."), seqId = 2),
        )
        (out2.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hey Emmanuel."

        val out3 = reduce(
            prev = out2.next,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Hey Emmanuel. Most recent thing"),
                seqId = 3,
            ),
        )
        (out3.next.events.single() as TimelineEvent.Confirmed).content shouldBe "Hey Emmanuel. Most recent thing"
    }

    @Test
    fun `stale cumulative prefix frames are dropped instead of appended`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by. Send a message."),
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by."),
                seqId = 2,
            ),
        )

        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe
            "Standing by. Send a message."
    }

    @Test
    fun `matching-tail frames are dropped instead of duplicated`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Standing by. Send a message."),
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Send a message."),
                seqId = 2,
            ),
        )

        (output.next.events.single() as TimelineEvent.Confirmed).content shouldBe
            "Standing by. Send a message."
    }

    @Test
    fun `defensive stream merge branches emit named telemetry`() {
        Telemetry.chatHotPathDebugEnabled.set(true)

        telemetryNamesForMerge("Stand", "Standing by.")
            .contains("streamSubscriber.cumulativeSnapshotReplaced") shouldBe true
        telemetryNamesForMerge("Standing by.", "Stand")
            .contains("streamSubscriber.staleFrameDropped") shouldBe true
        telemetryNamesForMerge("Standing by.", "by.")
            .contains("streamSubscriber.endsWithDropped") shouldBe true
    }

    @Test
    fun `stream text merge only uses snapshot branches when seq ids are available`() {
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = true,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = false,
        ) shouldBe StreamTextMergeResult(
            text = "HelloHello world",
            branch = StreamTextMergeBranch.APPEND,
            garbleRisk = false,
        )
    }

    @Test
    fun `stream text merge flags suspicious short appends for diagnostics`() {
        mergeStreamText(
            existing = "The previous assistant text is already long",
            incoming = " no",
            canUseSnapshotMerge = false,
        ) shouldBe StreamTextMergeResult(
            text = "The previous assistant text is already long no",
            branch = StreamTextMergeBranch.APPEND,
            garbleRisk = true,
        )
    }

    @Test
    fun `seqId dedup skips already-ingested stream frame`() {
        val seeded = reduce(
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hello"), seqId = 3)
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("Hel"), seqId = 2),
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
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `hydration drops persisted synthetic skill doc envelope`() {
        val result = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                UserMessage(id = "user-question", contentRaw = JsonPrimitive("What is the router status?")),
                UserMessage(id = "skill-envelope", contentRaw = JsonPrimitive(realCapturedSkillEnvelope())),
                AssistantMessage(id = "assistant-answer", contentRaw = JsonPrimitive("The router is online.")),
            ),
            timelineBeforeFetch = Timeline("conv-test"),
            currentTimeline = Timeline("conv-test"),
            diskRecords = emptyList(),
        )

        result.timeline.events.map { (it as TimelineEvent.Confirmed).serverId } shouldBe listOf("user-question", "assistant-answer")
        result.visibleEventCount shouldBe 2
    }

    @Test
    fun `semantic match dedupes hydrate then ws assistant with different server id`() {
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = "conv-test",
            serverMessagesChronological = listOf(
                AssistantMessage(
                    id = "rest-assistant",
                    contentRaw = JsonPrimitive("Let me check the more recent one then."),
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
            frame = AssistantMessage(
                id = "ws-assistant",
                contentRaw = JsonPrimitive("Let me check the more recent one then."),
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
            it.attrs["reason"] == "semanticIdentitySeen"
        } shouldBe true
    }

    @Test
    fun `reconciled final with real run id replaces iroh synthetic live row`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-300",
                contentRaw = JsonPrimitive("Hello"),
                runId = "iroh-run-client-synthetic",
                seqId = 1,
            ),
        ).next

        val output = reduce(
            prev = live,
            frame = AssistantMessage(
                id = "letta-msg-300",
                contentRaw = JsonPrimitive("Hello!"),
                runId = "server-run-real",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "letta-msg-300"
        event.runId shouldBe "server-run-real"
        event.content shouldBe "Hello!"
        output.emittedEvents shouldBe listOf(
            TimelineSyncEvent.StreamEventIngested("letta-msg-300", "assistant_message")
        )
    }

    @Test
    fun `synthetic live to real final uses incoming snapshot on content conflict`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-302",
                contentRaw = JsonPrimitive("synthetic partial that should not survive"),
                runId = "iroh-run-client-synthetic",
                seqId = null,
            ),
        ).next

        val output = reduce(
            prev = live,
            frame = AssistantMessage(
                id = "letta-msg-302",
                contentRaw = JsonPrimitive("final"),
                runId = "server-run-real",
                seqId = null,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.runId shouldBe "server-run-real"
        event.content shouldBe "final"
    }

    @Test
    fun `synthetic duplicate seq still reconciles real run id`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-303",
                contentRaw = JsonPrimitive("Hello!"),
                runId = "iroh-run-client-synthetic",
                seqId = 2,
            ),
        ).next

        val output = reduce(
            prev = live,
            frame = AssistantMessage(
                id = "letta-msg-303",
                contentRaw = JsonPrimitive("Hello!"),
                runId = "server-run-real",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
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
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-700",
                contentRaw = JsonPrimitive("Hello"),
                runId = "iroh-run-client-synthetic",
                seqId = 1,
            ),
        ).next

        val output = reduce(
            prev = live,
            frame = AssistantMessage(
                id = "letta-msg-700",
                contentRaw = JsonPrimitive(" world"),
                runId = "run-real-app-server",
                seqId = 2,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
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
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-701",
                contentRaw = JsonPrimitive("Hel"),
                runId = "iroh-run-client-synthetic",
                seqId = 5,
            ),
        ).next

        val output = reduce(
            prev = live,
            frame = AssistantMessage(
                id = "letta-msg-701",
                contentRaw = JsonPrimitive("Hello, world"),
                runId = "run-real-app-server",
                seqId = null,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.runId shouldBe "run-real-app-server"
        event.content shouldBe "Hello, world"
    }

    @Test
    fun `reconcile copy without run id does not duplicate live row with real run id`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-166",
                contentRaw = JsonPrimitive("Hey"),
                runId = "run-real-app-server",
                seqId = 3,
            ),
        ).next

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "letta-msg-166",
                    contentRaw = JsonPrimitive("Hey"),
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
    fun `reconcile copy with backend-minted id does not duplicate identical live row`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-166",
                contentRaw = JsonPrimitive("Hey"),
                runId = "run-real-app-server",
                seqId = 3,
            ),
        ).next

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "ui-msg-55781",
                    contentRaw = JsonPrimitive("Hey"),
                    runId = null,
                    seqId = null,
                )
            )
        )

        changed shouldBe 0
        mergedTimeline.events shouldHaveSize 1
        (mergedTimeline.events.single() as TimelineEvent.Confirmed).serverId shouldBe "letta-msg-166"
    }

    @Test
    fun `recent reconcile final assistant replaces live prefix row`() {
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-624",
                contentRaw = JsonPrimitive("Let"),
                runId = "local-run-15",
                seqId = 1,
            ),
        ).next

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "letta-msg-640",
                    contentRaw = JsonPrimitive("Let's find out — no duplicate here."),
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
        val live = reduce(
            frame = AssistantMessage(
                id = "letta-msg-301",
                contentRaw = JsonPrimitive("Hello"),
                runId = "iroh-run-client-synthetic",
                seqId = 1,
            ),
        ).next

        val (mergedTimeline, changed) = live.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "letta-msg-301",
                    contentRaw = JsonPrimitive("Hello!"),
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
        val streamedDraft = reduce(
            frame = AssistantMessage(
                // Last rotating backend id observed on the live stream.
                id = "letta-msg-5021",
                // Draft is missing the leading word ("Still") — first-word lag.
                contentRaw = JsonPrimitive(" kicking. Did anything else break?"),
                runId = "run-real-app-server",
                otid = "iroh-assistant-turn-42",
                seqId = 7,
            ),
        ).next

        streamedDraft.events shouldHaveSize 1

        val (mergedTimeline, changed) = streamedDraft.mergeServerMessages(
            listOf(
                AssistantMessage(
                    // Reconcile mints its OWN id, distinct from the streamed one.
                    id = "letta-msg-final-99",
                    contentRaw = JsonPrimitive("Still kicking. Did anything else break?"),
                    runId = "run-real-app-server",
                    otid = null,
                    seqId = null,
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
                AssistantMessage(
                    id = "letta-msg-203",
                    contentRaw = JsonPrimitive("old answer from a prior app server run"),
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
            frame = AssistantMessage(
                id = "letta-msg-203",
                contentRaw = JsonPrimitive("hello from iro"),
                runId = "local-run-new",
                seqId = 1,
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

    @Test
    fun `stream reducer hot path telemetry is disabled by default`() {
        Telemetry.chatHotPathDebugEnabled.set(false)
        Telemetry.clear()

        reduce(frame = AssistantMessage(id = "assistant-default", contentRaw = JsonPrimitive("hello")))

        Telemetry.snapshot().none {
            it.tag == "TimelineSync" && it.name.startsWith("streamSubscriber")
        } shouldBe true
    }

    @Test
    fun `stream reducer hot path telemetry can be enabled deliberately`() {
        Telemetry.chatHotPathDebugEnabled.set(true)
        Telemetry.clear()

        reduce(frame = AssistantMessage(id = "assistant-debug", contentRaw = JsonPrimitive("hello")))

        Telemetry.snapshot().any {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.ingested" &&
                it.level == Telemetry.Level.DEBUG
        } shouldBe true
    }

    @Test
    fun `late same-run assistant prefix with a new server id is dropped`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix",
                contentRaw = JsonPrimitive("Opening line"),
                runId = "run-1",
                seqId = 21,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "Opening line\n\nFinal body"
        output.next.liveCursor shouldBe "assistant-full"
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `late same-run blank assistant with a new server id is dropped after nonblank assistant`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-empty",
                contentRaw = JsonPrimitive(""),
                runId = "run-1",
                seqId = 21,
            ),
        )

        output.next.events shouldHaveSize 1
        (output.next.events.single() as TimelineEvent.Confirmed).serverId shouldBe "assistant-full"
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `post install replay prefix with seq one is dropped`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "rest-assistant",
                contentRaw = JsonPrimitive("Got it — I can check the latest build and keep going."),
                runId = "run-replayed-after-install",
                seqId = 24,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "ws-replayed-prefix",
                contentRaw = JsonPrimitive("Got"),
                runId = "run-replayed-after-install",
                seqId = 1,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "rest-assistant"
        event.content shouldBe "Got it — I can check the latest build and keep going."
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
    }

    @Test
    fun `assistant prefix from a different run is preserved`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Opening line\n\nFinal body"),
                runId = "run-1",
                seqId = 20,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix-next-run",
                contentRaw = JsonPrimitive("Opening line"),
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
            frame = AssistantMessage(id = "assistant-1", contentRaw = JsonPrimitive("hello"))
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
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-before-tool",
                contentRaw = JsonPrimitive("Yes — the `Agent` tool takes an optional `model` parameter"),
                runId = "run-1",
                seqId = 1,
            )
        ).next

        // Now the post-tool-return content stream starts. The first delta is just "Y"
        // — a single character that coincidentally is a prefix of the previous
        // assistant message. This must NOT be dropped.
        val output1 = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "cm-stream-post-tool-otid",
                contentRaw = JsonPrimitive("Y"),
                runId = "run-1",
                seqId = 1,  // FIRST frame for this new otid — genuinely new message
                otid = "post-tool-otid",
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
            frame = AssistantMessage(
                id = "cm-stream-post-tool-otid",
                contentRaw = JsonPrimitive("es — confirmed working at both layers:"),
                runId = "run-1",
                seqId = 2,
                otid = "post-tool-otid",
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
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-final",
                contentRaw = JsonPrimitive("Nah, that was just the bit. Real answer: agents stay off the relay."),
                runId = null,
            ),
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix",
                contentRaw = JsonPrimitive("N"),
                runId = "local-run-39",
                seqId = 1,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-final"
        event.content shouldBe "Nah, that was just the bit. Real answer: agents stay off the relay."
        output.emittedEvents shouldBe emptyList()
    }

    @Test
    fun `assistant prefix replay from local run is dropped after iroh final`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Yeah — still seeing it. Smells like a dedup issue."),
                runId = "iroh-run-123",
                seqId = 12,
            ),
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-prefix",
                contentRaw = JsonPrimitive("Yeah"),
                runId = "local-run-12",
                seqId = 1,
            ),
        )

        output.next.events shouldHaveSize 1
        val event = output.next.events.single() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-full"
        event.content shouldBe "Yeah — still seeing it. Smells like a dedup issue."
        output.emittedEvents shouldBe emptyList()
    }



    @Test
    fun `failed turn with sole short assistant fragment removes tail row`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-fragment",
                contentRaw = JsonPrimitive("No"),
                runId = "run-failed",
                seqId = 1,
            ),
        ).next

        val cleaned = seeded.cleanupAbandonedAssistantFragments(
            runId = "run-failed",
            turnId = "turn-failed",
            reason = "turn_done_failed",
        ).timeline

        cleaned.events shouldHaveSize 0
    }


    @Test
    fun `cleanup uses observed real run id when terminal frame carries synthetic run id`() {
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-fragment",
                contentRaw = JsonPrimitive("Ok"),
                runId = "run-real-observed",
                seqId = 1,
            ),
        ).next

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
        val shortAssistant = reduce(
            frame = AssistantMessage(
                id = "assistant-ok",
                contentRaw = JsonPrimitive("OK"),
                runId = "run-legit",
                seqId = 1,
            ),
        ).next
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
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-ok",
                contentRaw = JsonPrimitive("OK"),
                runId = "run-legit",
                seqId = 1,
            ),
        ).next

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
        val withFull = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("I can help clean up the lifecycle."),
                runId = "run-terminal",
                seqId = 12,
            ),
        ).next
        val withFragment = withFull.append(
            (AssistantMessage(
                id = "assistant-orphan-i",
                contentRaw = JsonPrimitive("I"),
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
        val withFull = reduce(
            frame = AssistantMessage(
                id = "assistant-full",
                contentRaw = JsonPrimitive("Nah, that was the full response."),
                runId = "run-disconnect",
                seqId = 20,
            ),
        ).next
        val withFragment = withFull.append(
            AssistantMessage(
                id = "assistant-orphan-n",
                contentRaw = JsonPrimitive("N"),
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
        val withFragment = reduce(
            frame = AssistantMessage(
                id = "assistant-orphan-i",
                contentRaw = JsonPrimitive("I"),
                runId = "local-run-terminal",
                seqId = 1,
            ),
        ).next

        val cleaned = withFragment.cleanupAbandonedAssistantFragments("iroh-run-terminal", "turn-terminal", "turn_done_failed").timeline

        cleaned.events shouldHaveSize 0
    }


    @Test
    fun `terminal cleanup with synthetic run id removes observed real run fragment`() {
        val withFragment = reduce(
            frame = AssistantMessage(
                id = "assistant-real-run-fragment",
                contentRaw = JsonPrimitive("I"),
                runId = "run-app",
                seqId = 1,
            ),
        ).next

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
        val withFragment = reduce(
            frame = AssistantMessage(
                id = "assistant-orphan-reconcile",
                contentRaw = JsonPrimitive("I"),
                runId = "run-reconcile",
                seqId = 1,
            ),
        ).next
        val cleaned = withFragment.cleanupAbandonedAssistantFragments(
            runId = "run-reconcile",
            turnId = "turn-reconcile",
            reason = "turn_done_failed",
        ).timeline

        val (mergedTimeline, changed) = cleaned.mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "assistant-orphan-reconcile",
                    contentRaw = JsonPrimitive("I"),
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
        val earlier = reduce(
            frame = AssistantMessage(
                id = "assistant-ok",
                contentRaw = JsonPrimitive("OK"),
                runId = "run-terminal",
                seqId = 1,
            ),
        ).next
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
        val seeded = reduce(
            frame = AssistantMessage(
                id = "assistant-before-tool",
                contentRaw = JsonPrimitive("Yes — earlier answer before tool."),
                runId = "run-success",
                seqId = 1,
            ),
        ).next
        val withContinuation = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "assistant-post-tool",
                contentRaw = JsonPrimitive("Y"),
                runId = "run-success",
                seqId = 1,
                otid = "post-tool",
            ),
        ).next
        val completed = reduce(
            prev = withContinuation,
            frame = AssistantMessage(
                id = "assistant-post-tool",
                contentRaw = JsonPrimitive("es, that worked."),
                runId = "run-success",
                seqId = 2,
                otid = "post-tool",
            ),
        ).next

        completed.events shouldHaveSize 2
        val event = completed.events.last() as TimelineEvent.Confirmed
        event.serverId shouldBe "assistant-post-tool"
        event.content shouldBe "Yes, that worked."
    }

    @Test
    fun `semantic duplicate detection stays bounded on long histories`() {
        val longHistory = Timeline(
            conversationId = "conv-test",
            events = (1..5_000).map { index ->
                TimelineEvent.Confirmed(
                    position = index.toDouble(),
                    otid = "otid-$index",
                    content = "historical message $index",
                    serverId = "server-$index",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = parseTimelineInstant("1970-01-01T00:00:00Z"),
                    runId = "run-$index",
                    stepId = null,
                )
            }.toPersistentList(),
        )

        val output = reduce(
            prev = longHistory,
            frame = AssistantMessage(
                id = "assistant-duplicate",
                contentRaw = JsonPrimitive("historical message 4999"),
                runId = "run-4999",
            ),
        )

        output.next.events shouldHaveSize 5_000
        output.emittedEvents shouldBe emptyList()
    }

    @Test
    fun `REAL WIRE rotating per-fragment ids with NULL otid must reduce to one row h30cy`() {
        // Faithful replay of the ACTUAL Iroh wire shape captured headlessly via
        // app-server-iroh-probe: a single assistant reply arrives as N stream_delta
        // fragments, each with a NEW sequential letta-msg id and NO otid (otid=null).
        // Prior tests hardcoded a shared otid, which the real wire does NOT provide —
        // that false assumption is why fixes passed tests but duplicated on device.
        // Cumulative content grows per fragment; all share one real run id.
        val runId = "run-real-app-server"
        val fragments = listOf("Hey", "Hey back", "Hey back.", "Hey back. Still", "Hey back. Still here.")
        var tl = timeline()
        fragments.forEachIndexed { i, cumulative ->
            tl = reduce(
                prev = tl,
                frame = AssistantMessage(
                    id = "letta-msg-${1312 + i}", // rotating, +1 per fragment (real wire)
                    contentRaw = JsonPrimitive(cumulative),
                    runId = runId,
                    otid = null, // REAL WIRE: assistant stream_delta carries no otid
                    seqId = i,
                ),
            ).next
        }
        // The whole reply must collapse to exactly ONE assistant row with the full text.
        tl.events shouldHaveSize 1
        val event = tl.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "Hey back. Still here."
    }

    @Test
    fun `REAL reconcile dup ui-msg final with null run collapses into streamed row h30cy`() {
        // Ground truth (admin message.list): the reconciled FINAL has id==otid==ui-msg-*,
        // run_id=NULL, and content that is a SUPERSET (or near-equal, first-word-lag
        // means not byte-identical) of the streamed row. Different otid + null run +
        // non-exact content defeated every match, so it inserted as a 2nd row.
        // The streamed row is a live assistant row (synthetic otid, real run).
        val streamedRow = reduce(
            frame = AssistantMessage(
                id = "letta-msg-1799",
                contentRaw = JsonPrimitive("m Lester, a dedicated test agent"), // first-word-lag: missing "I'"
                runId = "local-run-30",
                otid = "provider-assistant-1-abc",
                seqId = 42,
            ),
        ).next
        // The reconcile snapshot: ui-msg id/otid, NULL run, FULL text.
        val reconciled = listOf(
            AssistantMessage(
                id = "ui-msg-9006572",
                contentRaw = JsonPrimitive("I'm Lester, a dedicated test agent"),
                runId = null,
                otid = "ui-msg-9006572",
                seqId = null,
            )
        )
        val (afterReconcile, _) = streamedRow.mergeServerMessages(reconciled)
        val assistantRows = afterReconcile.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(1, assistantRows.size)
        assertEquals("I'm Lester, a dedicated test agent", assistantRows.single().content)
    }

    @Test
    fun `reconcile ui-msg final collapses even when liveCursor moved off the streamed row h30cy`() {
        // h30cy RESURFACE: the earlier fix required the match to be the liveCursor
        // row, but at reconcile time liveCursor has often moved off the streamed
        // reply (e.g. a later turn started, or it was cleared), so the duplicate
        // slipped through. The null-run signature is the correct discriminator;
        // liveCursor must NOT be required.
        var tl = reduce(
            frame = AssistantMessage(
                id = "letta-msg-1799", contentRaw = JsonPrimitive("I'm Lester, a dedicated test agent"),
                runId = "local-run-30", otid = "provider-assistant-1-abc", seqId = 42,
            ),
        ).next
        // liveCursor moves OFF the streamed row (a later user/other event advances it).
        tl = tl.copy(liveCursor = "some-other-server-id")
        val reconciled = listOf(
            AssistantMessage(
                id = "ui-msg-9006572",
                contentRaw = JsonPrimitive("I'm Lester, a dedicated test agent for validating mobile"),
                runId = null, otid = "ui-msg-9006572", seqId = null,
            )
        )
        val (after, _) = tl.mergeServerMessages(reconciled)
        val rows = after.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(1, rows.size)
        assertEquals("I'm Lester, a dedicated test agent for validating mobile", rows.single().content)
    }

    private fun reduce(
        prev: Timeline = timeline(),
        frame: com.letta.mobile.data.model.LettaMessage,
        pendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage> = persistentMapOf(),
    ): TimelineReducerOutput = reduceStreamFrame(
        TimelineReducerInput(
            prev = prev,
            frame = frame,
            pendingToolReturnsByCallId = pendingToolReturnsByCallId,
        )
    )

    private fun timeline(): Timeline = Timeline(conversationId = "conv-test")

    private fun telemetryNamesForMerge(existing: String, incoming: String): Set<String> {
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

    private fun realCapturedSkillEnvelope(): String = """
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

private infix fun <T> T.shouldBe(expected: T) {
    assertEquals(expected, this)
}

private infix fun Collection<*>.shouldHaveSize(expected: Int) {
    assertEquals(expected, size)
}
