package com.letta.mobile.data.timeline

import com.letta.mobile.util.textOrNull

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test


class TimelineStreamReconciliationTest : TimelineStreamReducerFixture() {
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
                AssistantMessage(id = "skill-content", contentRaw = JsonPrimitive(skillContent)),
                AssistantMessage(id = "answer", contentRaw = JsonPrimitive("Performance is healthy.")),
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
        output.emittedEvents shouldBe emptyList()
        output.notification shouldBe null
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
            it.attrs["reason"]?.textOrNull == "semanticIdentitySeen"
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
    fun `ui-msg final without exact alias preserves identical live row`() {
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

        changed shouldBe 1
        mergedTimeline.events shouldHaveSize 2
        (mergedTimeline.events.first() as TimelineEvent.Confirmed).serverId shouldBe "letta-msg-166"
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
                logicalMessageId = "lm-301",
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

