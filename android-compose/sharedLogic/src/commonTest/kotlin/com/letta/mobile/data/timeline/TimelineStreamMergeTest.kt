package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test


class TimelineStreamMergeTest : TimelineStreamReducerFixture() {
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
                logicalMessageId = "otid-assistant-1",
                seqId = 1,
            )
        ).next

        val output = reduce(
            prev = seeded,
            frame = AssistantMessage(
                id = "letta-msg-5021",
                contentRaw = JsonPrimitive("Got it — Iroh transport is streaming the response."),
                runId = "run-real-app-server",
                logicalMessageId = "otid-assistant-1",
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
    fun `stream text merge EQUAL CUMULATIVE fire when EITHER ordering or cumulative-shape signal is present`() {
        // letta-mobile-bn008 + letta-mobile-wucn: the new contract is that
        // EQUAL/CUMULATIVE branches fire when EITHER the per-frame seq-id
        // ordering signal (canUseSnapshotMerge) OR the upstream-derived
        // stream-shape signal (isCumulativeStream) is present. The
        // seq-gated branches below (STALE, SUFFIX_DUPLICATE, SNAPSHOT_CONFLICT)
        // remain gated on canUseSnapshotMerge alone because they DROP text and
        // need the full ordering signal.

        // 1. Both gates true (typical seq-id-carrying cumulative snapshot).
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = true,
            isCumulativeStream = true,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        // 2. canUseSnapshotMerge only (legacy seq-id gate, no shape signal).
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = true,
            isCumulativeStream = false,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        // 3. isCumulativeStream only (bn008 cascade: stable otid confirms the
        //    upstream is cumulative, but seq ids may be missing on every frame).
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = false,
            isCumulativeStream = true,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        // 4. Neither gate, but the incoming body already contains the reply and
        //    is longer. That is a snapshot, not a new token, so it replaces.
        //    Identical text (below) still APPENDs: that is the wucn token.
        mergeStreamText(
            existing = "Hello",
            incoming = "Hello world",
            canUseSnapshotMerge = false,
            isCumulativeStream = false,
        ) shouldBe StreamTextMergeResult(
            text = "Hello world",
            branch = StreamTextMergeBranch.CUMULATIVE,
            garbleRisk = false,
        )

        assertEqualMergeGateBehavior()
    }

    private fun assertEqualMergeGateBehavior() {
        // EQUAL analog: same gate logic.
        mergeStreamText(
            existing = "Hi",
            incoming = "Hi",
            canUseSnapshotMerge = false,
            isCumulativeStream = true,
        ) shouldBe StreamTextMergeResult(
            text = "Hi",
            branch = StreamTextMergeBranch.EQUAL,
            garbleRisk = false,
        )
        mergeStreamText(
            existing = "Hi",
            incoming = "Hi",
            canUseSnapshotMerge = false,
            isCumulativeStream = false,
        ) shouldBe StreamTextMergeResult(
            text = "HiHi",
            branch = StreamTextMergeBranch.APPEND,
            garbleRisk = false,
        )
    }

    @Test
    fun `incremental forward mode still rejects non-forward stale and suffix duplicate frames`() {
        mergeStreamText(
            existing = "I'm Lester and still here",
            incoming = "I'm",
            canUseSnapshotMerge = true,
            incomingIsForwardDelta = false,
            incrementalForwardAppend = true,
        ) shouldBe StreamTextMergeResult(
            text = "I'm Lester and still here",
            branch = StreamTextMergeBranch.STALE,
            garbleRisk = false,
        )

        mergeStreamText(
            existing = "Still debugging now?",
            incoming = "now?",
            canUseSnapshotMerge = true,
            incomingIsForwardDelta = false,
            incrementalForwardAppend = true,
        ) shouldBe StreamTextMergeResult(
            text = "Still debugging now?",
            branch = StreamTextMergeBranch.SUFFIX_DUPLICATE,
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
}

