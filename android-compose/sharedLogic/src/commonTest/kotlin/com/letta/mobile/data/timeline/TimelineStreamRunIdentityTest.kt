package com.letta.mobile.data.timeline


import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class TimelineStreamRunIdentityTest : TimelineStreamReducerFixture() {
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
    fun `first fragment committed before run-id promotion still grows into one row w0ctr`() {
        // The orphan-first-chunk bug. Every prior fixture gives all fragments the same
        // otid and run id FROM FRAGMENT 1, so none of them cover the real failure: the
        // opening fragment is committed while the transport still holds a SYNTHETIC run
        // id, and promotion to the real run id lands with the second fragment. The
        // forward-growth net used isCompatibleAssistantPrefixRunId, which rejects
        // synthetic-vs-real, so fragment 2 appended a new row and "It" was stranded as
        // its own bubble beneath the finished reply.
        var tl = reduce(
            frame = AssistantMessage(
                id = "letta-msg-1799",
                contentRaw = JsonPrimitive("It"),
                runId = "iroh-run-9f3c1e", // synthetic: promotion has not landed yet
                otid = null,
                seqId = 0,
            ),
        ).next
        tl.events shouldHaveSize 1

        listOf("It looks", "It looks like", "It looks like it worked.").forEachIndexed { i, cumulative ->
            tl = reduce(
                prev = tl,
                frame = AssistantMessage(
                    id = "letta-msg-${1800 + i}", // rotating per fragment, as the real wire does
                    contentRaw = JsonPrimitive(cumulative),
                    runId = "run-real-42", // promoted to the real run id
                    otid = null,
                    seqId = i + 1,
                ),
            ).next
        }

        tl.events shouldHaveSize 1
        val event = tl.events.single() as TimelineEvent.Confirmed
        event.content shouldBe "It looks like it worked."
    }

    @Test
    fun `post-tool continuation still starts its own row after the run-id gate change w0ctr`() {
        // Guards the relaxation: a post-tool continuation arrives SHORTER than the prior
        // assistant row, so it is not forward growth and must remain a separate message.
        // This is the case the strict discriminator protects, and it must survive.
        var tl = reduce(
            frame = AssistantMessage(
                id = "letta-msg-3000",
                contentRaw = JsonPrimitive("Let me check that for you."),
                runId = "iroh-run-aaa",
                otid = null,
                seqId = 0,
            ),
        ).next

        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "letta-msg-3001",
                contentRaw = JsonPrimitive("Done"), // shorter: a new message, not growth
                runId = "run-real-99",
                otid = null,
                seqId = 1,
            ),
        ).next

        tl.events shouldHaveSize 2
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
    fun `ui-msg final without exact alias preserves identical content rows and emits telemetry`() {
        Telemetry.clear()
        var tl = reduce(frame = AssistantMessage(
            id = "cm-stream-logical-live", contentRaw = JsonPrimitive("same"), runId = "run-1", otid = "logical-live", seqId = 1,
        )).next
        tl = tl.mergeServerMessages(listOf(
            AssistantMessage(id = "ui-msg-final", contentRaw = JsonPrimitive("same"), runId = null, otid = "ui-msg-final", seqId = null),
        )).first
        assertEquals(2, tl.events.filterIsInstance<TimelineEvent.Confirmed>().count { it.messageType == TimelineMessageType.ASSISTANT })
        assertTrue(Telemetry.snapshot().any { it.name == "recentReconcile.unresolvedCrossBoundaryIdentity" })
    }

    @Test
    fun `REAL reconcile ui-msg final without exact identity remains distinct h30cy`() {
        // A ui-msg final has no exact link to this streamed row. Similar content
        // and a missing run id are insufficient identity evidence.
        val streamedRow = reduce(
            frame = AssistantMessage(
                id = "letta-msg-1799",
                contentRaw = JsonPrimitive("m Lester, a dedicated test agent"), // first-word-lag: missing "I'"
                runId = "local-run-30",
                logicalMessageId = "provider-assistant-1-abc",
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
        assertEquals(2, assistantRows.size)
        assertEquals("m Lester, a dedicated test agent", assistantRows[0].content)
        assertEquals("I'm Lester, a dedicated test agent", assistantRows[1].content)
    }

    @Test
    fun `reconcile ui-msg final without exact identity stays distinct when cursor moved h30cy`() {
        // Cursor position does not strengthen an otherwise unresolved identity.
        var tl = reduce(
            frame = AssistantMessage(
                id = "letta-msg-1799", contentRaw = JsonPrimitive("I'm Lester, a dedicated test agent"),
                runId = "local-run-30", logicalMessageId = "provider-assistant-1-abc", seqId = 42,
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
        assertEquals(2, rows.size)
        assertEquals("I'm Lester, a dedicated test agent", rows[0].content)
        assertEquals("I'm Lester, a dedicated test agent for validating mobile", rows[1].content)
    }

    @Test
    fun `distinct ui-msg finals without aliases are never content deduped h30cy`() {
        // Distinct persisted IDs remain distinct even when their content matches.
        var tl = reduce(
            frame = AssistantMessage(
                id = "letta-msg-3255", contentRaw = JsonPrimitive("Hey"),
                runId = "local-run-43", logicalMessageId = "provider-assistant-1-x", seqId = 22,
            ),
        ).next
        tl = reduce(prev = tl, frame = AssistantMessage(
            id = "letta-msg-3256", contentRaw = JsonPrimitive("."),
            runId = "local-run-43", logicalMessageId = "provider-assistant-1-x", seqId = 23,
        )).next
        // streamed row is now "Hey."; reconcile returns TWO identical "Hey." finals.
        val reconciled = listOf(
            AssistantMessage(id = "ui-msg-9006596",
                 contentRaw = JsonPrimitive("Hey."),
                 runId = null,
                 otid = "ui-msg-9006596",
                 seqId = null),
                
            AssistantMessage(id = "ui-msg-9006598",
                 contentRaw = JsonPrimitive("Hey."),
                 runId = null,
                 otid = "ui-msg-9006598",
                 seqId = null),
                
        )
        val (after, _) = tl.mergeServerMessages(reconciled)
        val rows = after.events.filterIsInstance<TimelineEvent.Confirmed>().filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(3, rows.size, "rows: " + rows.joinToString("|"){ it.content })
        assertEquals(listOf("Hey.", "Hey.", "Hey."), rows.map { it.content })
    }

    @Test
    fun `identical ui-msg finals in later polls remain distinct without aliases h30cy`() {
        // Poll timing and identical content do not establish identity.
        var tl = reduce(frame = AssistantMessage(id = "letta-msg-1",
             contentRaw = JsonPrimitive("Hey"),
             runId = "local-run-1",
             logicalMessageId = "provider-assistant-1-x",
             seqId = 1)).next
        tl = reduce(prev = tl,
             frame = AssistantMessage(id = "letta-msg-2",
             contentRaw = JsonPrimitive("."),
             runId = "local-run-1",
             logicalMessageId = "provider-assistant-1-x",
             seqId = 2)).next
        // poll 1
        tl = tl.mergeServerMessages(listOf(AssistantMessage(id = "ui-msg-596",
             contentRaw = JsonPrimitive("Hey."),
             runId = null,
             otid = "ui-msg-596",
             seqId = null))).first
        // poll 2 (8s later): a NEW server-persisted identical copy
        tl = tl.mergeServerMessages(listOf(AssistantMessage(id = "ui-msg-598",
             contentRaw = JsonPrimitive("Hey."),
             runId = null,
             otid = "ui-msg-598",
             seqId = null))).first
        val rows = tl.events.filterIsInstance<TimelineEvent.Confirmed>().filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(3, rows.size, "rows: " + rows.joinToString("|"){ it.content })
    }

    @Test
    fun `reconcile final does not overwrite an unrelated older reply that is a substring h30cy`() {
        // Neither the older reply nor the streamed row has an exact alias for the
        // final, so neither may be overwritten based on substring similarity.
        // older distinct reply — seed it as a reconciled null-run row via merge.
        var tl = Timeline(conversationId = "c").mergeServerMessages(listOf(
            AssistantMessage(id = "ui-msg-old", contentRaw = JsonPrimitive("Hey"), runId = null, otid = "ui-msg-old", seqId = null)
        )).first
        // the actual STREAMED row being finalized (real run id).
        tl = reduce(prev = tl, frame = AssistantMessage(
            id = "letta-msg-9", contentRaw = JsonPrimitive("Hey there, how are"),
            runId = "local-run-9", logicalMessageId = "provider-assistant-1-z", seqId = 9,
        )).next
        // reconcile final "Hey there, how are you?" contains BOTH "Hey" (old) and
        // the streamed "Hey there, how are". Must replace the STREAMED row only.
        val (after, _) = tl.mergeServerMessages(listOf(
            AssistantMessage(id = "ui-msg-final",
                 contentRaw = JsonPrimitive("Hey there, how are you?"),
                 runId = null,
                 otid = "ui-msg-final",
                 seqId = null)
        ))
        val rows = after.events.filterIsInstance<TimelineEvent.Confirmed>().filter { it.messageType == TimelineMessageType.ASSISTANT }
        // Both prior rows are preserved and the unresolved final is appended.
        assertEquals(3, rows.size, "rows: " + rows.joinToString("|"){ it.content })
        assertEquals("Hey", rows[0].content)
        assertEquals("Hey there, how are", rows[1].content)
        assertEquals("Hey there, how are you?", rows[2].content)
    }

    @Test
    fun `delayed reconcile final can collapse older streamed row behind newer turn h30cy`() {
        var tl = reduce(frame = AssistantMessage(
            id = "cm-stream-provider-assistant-old",
            contentRaw = JsonPrimitive(", got it — so the original theory still holds. Clean on restart dupes only in streaming " +
                "path The persistence layer is fine"),
                
            runId = "local-run-103",
            logicalMessageId = "provider-assistant-old",
            seqId = 1,
        )).next
        tl = reduce(prev = tl, frame = AssistantMessage(
            id = "cm-stream-provider-assistant-new",
            contentRaw = JsonPrimitive("Newer turn has already started"),
            runId = "local-run-104",
            logicalMessageId = "provider-assistant-new",
            seqId = 1,
        )).next

        val (after, _) = tl.mergeServerMessages(listOf(
            AssistantMessage(
                id = "ui-msg-9006716",
                contentRaw = JsonPrimitive("Ah, got it — so the original theory still holds. Clean on restart dupes only in streaming " +
                    "path. The persistence layer is fine."),
                    
                runId = null,
                otid = "ui-msg-9006716",
                seqId = null,
            )
        ))

        val rows = after.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(3, rows.size, "rows: " + rows.joinToString("|") { it.content })
        assertEquals(", got it — so the original theory still holds. Clean on restart dupes only in streaming " +
            "path The persistence layer is fine",
             rows[0].content)
        assertEquals("Newer turn has already started", rows[1].content)
        assertEquals("Ah, got it — so the original theory still holds. Clean on restart dupes only in streaming " +
            "path. The persistence layer is fine.",
             rows[2].content)
    }

    @Test
    fun `reducer stamps the input agentId onto the produced Confirmed event c4igq4`() {
        val out = reduce(
            frame = AssistantMessage(id = "assistant-agent-scoped", contentRaw = JsonPrimitive("hi from A")),
            agentId = "agent-A",
        )
        val confirmed = out.next.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.serverId == "assistant-agent-scoped" }
        confirmed.agentId shouldBe "agent-A"
    }

    @Test
    fun `run-id-less cumulative fragments grow one row instead of stranding the opening bubble lf4hh`() {
        // On-device symptom (desktop, 2026-09-03): one reply rendered as TWO bubbles,
        // "Hey" above "Hey there! Still connected." The frames carried no otid and NO
        // run id, so the otid merge, the same-run prefix skip and the forward-growth
        // merge all missed and the second cumulative fragment appended a new row.
        var tl = reduce(frame = AssistantMessage(id = "letta-msg-1", contentRaw = JsonPrimitive("Hey"))).next
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(id = "letta-msg-2", contentRaw = JsonPrimitive("Hey there! Still connected.")),
        ).next

        val rows = tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(1, rows.size, "rows: " + rows.joinToString("|") { it.content })
        rows.single().content shouldBe "Hey there! Still connected."
    }

    @Test
    fun `blank-run growth does not fire when the incoming fragment carries a real run id lf4hh`() {
        // #827 shape: a settled reconciled row (runId = null) must not be grown by a
        // streamed fragment that carries its own real run id, however similar the text.
        var tl = timeline().mergeServerMessages(
            listOf(
                AssistantMessage(id = "ui-msg-old", contentRaw = JsonPrimitive("Hey"), runId = null, otid = "ui-msg-old")
            )
        ).first
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(
                id = "letta-msg-9",
                contentRaw = JsonPrimitive("Hey there, how are"),
                runId = "local-run-9",
                logicalMessageId = "provider-assistant-1-z",
                seqId = 9,
            ),
        ).next

        val rows = tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(2, rows.size, "rows: " + rows.joinToString("|") { it.content })
    }

    @Test
    fun `blank-run growth never absorbs a settled older reply with the same prefix lf4hh`() {
        // The date window, not the run id, is what keeps an older run-id-less reply
        // safe when a NEW turn opens with text that happens to extend it.
        var tl = timeline().mergeServerMessages(
            listOf(
                AssistantMessage(
                    id = "ui-msg-old",
                    contentRaw = JsonPrimitive("Hey"),
                    date = "2020-01-01T00:00:00Z",
                    runId = null,
                    otid = "ui-msg-old",
                )
            )
        ).first
        tl = reduce(
            prev = tl,
            frame = AssistantMessage(id = "letta-msg-9", contentRaw = JsonPrimitive("Hey there, a brand new turn.")),
        ).next

        val rows = tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals(2, rows.size, "rows: " + rows.joinToString("|") { it.content })
        rows[0].content shouldBe "Hey"
        rows[1].content shouldBe "Hey there, a brand new turn."
    }
}

