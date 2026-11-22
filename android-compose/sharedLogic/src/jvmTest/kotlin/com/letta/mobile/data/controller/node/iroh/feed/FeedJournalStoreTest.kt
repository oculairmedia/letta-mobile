package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The durable turn journal: restart survival, resumable reads (no gap, no duplicate), epoch resync, retention, the disk
 * ceiling, corrupt and torn files, and the rule that the host never synthesizes a terminal. The expected values are
 * written out here, not read back through the production helpers that produced them.
 */
class FeedJournalStoreTest : FeedJournalTestBase() {
    @Test
    fun aFinishedTurnSurvivesAWrapperRestartUnderANewEpoch() {
        val dir = directory()
        val first = start(dir)
        first.writerFor(Conv.default, TurnName("cm-1")).also { writer ->
            writer.play(listOf(Delta.toolCall(CallId("t1")), Delta.toolReturn(CallId("t1")), Delta.stopReason()), Ending.Terminal)
            writer.noteInitiatorMissedTail()
        }
        val before = first.drainOf(Conv.default, null).flatMap { it.entries }

        val second = start(dir)

        assertTrue(second.epoch > first.epoch, "a restart must issue a greater epoch")
        assertTrue(second.knowsTurn(Conv.default, TurnName("cm-1")), "a re-sent input for a finished turn is still a duplicate")
        val after = second.drainOf(Conv.default, null).flatMap { it.entries }
        assertEquals(
            before.map { it.seq to it.payload },
            after.map { it.seq to it.payload },
            "every journaled event is still there, in order",
        )
        val replayed = second.replayFor(Conv.default, TurnName("cm-1"))?.deltas
            ?.map { Json.parseToJsonElement(it).jsonObject.text("message_type") }
        assertEquals(listOf("tool_call_message", "tool_return_message", "stop_reason"), replayed)
    }

    @Test
    fun theUserEchoIsInTheFeedButNeverInTheInitiatorsRedialReplay() {
        val store = start(directory())
        val echo = Delta("""{"message_type":"user_message","id":"cm-user-cm-1","otid":"cm-1","content":"hi"}""")
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        writer.append(echo)
        writer.play(listOf(Delta.assistant(Text("hello")), Delta.stopReason()), Ending.Terminal)
        writer.noteInitiatorMissedTail()

        val fed = store.drainOf(Conv.default, null).flatMap { it.entries }.mapNotNull { it.payload?.text("message_type") }
        assertEquals(listOf("user_message", "assistant_message", "stop_reason"), fed, "a viewer reading the feed sees the prompt")
        val replayed = store.replayFor(Conv.default, TurnName("cm-1"))?.deltas
            ?.map { Json.parseToJsonElement(it).jsonObject.text("message_type") }
        assertEquals(listOf("assistant_message", "stop_reason"), replayed, "the initiator re-owns its prompt")
    }

    @Test
    fun theReplayOfAFinishedTurnCarriesItsRealTerminalAndIsOfferedUntilDelivered() {
        val dir = directory()
        val first = start(dir)
        val writer = first.writerFor(Conv.default, TurnName("cm-1"))
        writer.play(listOf(Delta.assistant(Text("hel")), Delta.assistant(Text("hello")), Delta.stopReason()), Ending.Terminal)
        assertNull(first.replayFor(Conv.default, TurnName("cm-1")), "an initiator that saw the whole turn is owed nothing")
        writer.noteInitiatorMissedTail()

        val restarted = start(dir)
        val replay = assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1")))
        assertTrue(replay.terminalObserved)
        assertTrue(replay.deltas.last().contains("stop_reason"))
        assertTrue(replay.deltas.any { it.contains("\"hello\"") })
        assertFalse(replay.deltas.any { it.contains("hel\"") }, "the superseded snapshot is not replayed")
        assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1")), "offered again until the write is confirmed")
        replay.delivered()
        assertNull(restarted.replayFor(Conv.default, TurnName("cm-1")))
        assertNull(start(dir).replayFor(Conv.default, TurnName("cm-1")), "delivery is durable too")
    }

    @Test
    fun reconnectingFromACursorYieldsExactlyTheMissedDeltasWithNoGapAndNoDuplicate() {
        val store = start(directory())
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        listOf(Delta.toolCall(CallId("t1")), Delta.toolReturn(CallId("t1")), Delta.toolCall(CallId("t2"))).forEach(writer::append)
        val applied = store.pageOf(Conv.default, null, 100)
        assertEquals(ConversationFeedWire.MODE_SNAPSHOT, applied.mode)
        val seen = applied.seqs().toMutableList()

        listOf(Delta.toolReturn(CallId("t2")), Delta.toolCall(CallId("t3")), Delta.toolReturn(CallId("t3"))).forEach(writer::append)
        writer.close(terminalObserved = false)
        val resumed = store.pageOf(Conv.default, applied.cursor, 100)

        assertEquals(ConversationFeedWire.MODE_REPLAY, resumed.mode)
        assertNull(resumed.resyncReason)
        assertEquals(
            listOf("tool_return_message", "tool_call_message", "tool_return_message", "turn_closed"),
            resumed.payloadTypes().zip(resumed.kinds()).map { (type, kind) -> type ?: kind },
        )
        seen += resumed.seqs()
        assertEquals((1..seen.size).map { it.toLong() }, seen, "the union of both reads is contiguous: no gap, no duplicate")
        assertEquals(seen.size.toLong(), resumed.headSeq)
    }

    @Test
    fun pagingByLimitNeverSkipsOrRepeatsAnEntry() {
        val store = start(directory())
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        (1..25).forEach { writer.append(Delta.toolCall(CallId("t$it"))) }
        writer.close(terminalObserved = false)

        val pages = store.drainOf(Conv.default, null, limit = 4)

        assertTrue(pages.size > 1)
        assertTrue(pages.dropLast(1).all { it.hasMore })
        assertFalse(pages.last().hasMore)
        assertEquals((1L..27L).toList(), pages.flatMap { it.seqs() }, "turn_open + 25 calls + turn_closed, once each, in order")
    }

    @Test
    fun anIdleConversationEmitsZeroDeltas() {
        val store = start(directory())
        store.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        val caughtUp = store.pageOf(Conv.default, null, 100)

        val idle = store.pageOf(Conv.default, caughtUp.cursor, 100)

        assertEquals(ConversationFeedWire.MODE_REPLAY, idle.mode)
        assertTrue(idle.entries.isEmpty())
        assertFalse(idle.hasMore)
        assertEquals(caughtUp.cursor, idle.cursor, "asking again from the head returns the same head")
        assertEquals(ConversationFeedWire.MODE_REPLAY, store.pageOf(Conv.default, idle.cursor, 100).mode)
    }

    @Test
    fun aCursorFromBeforeARestartForcesAResyncWithTheRetainedJournal() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        val oldCursor = first.pageOf(Conv.default, null, 100).cursor

        val second = start(dir)
        val page = second.pageOf(Conv.default, oldCursor, 100)

        assertEquals(ConversationFeedWire.MODE_SNAPSHOT, page.mode)
        assertEquals(ConversationFeedWire.RESYNC_EPOCH_CHANGED, page.resyncReason)
        assertEquals(second.epoch, page.cursor.epoch)
        assertEquals(listOf("turn_open", "stream_delta", "turn_closed"), page.kinds())
        assertEquals(ConversationFeedWire.MODE_REPLAY, second.pageOf(Conv.default, page.cursor, 100).mode, "the snapshot's cursor resumes")
    }

    @Test
    fun aCursorBehindTheHorizonOrBeyondTheHeadIsAResync() {
        val store = start(directory(), FeedRetention(maxEntriesPerConversation = 8))
        (1..6).forEach { turn -> store.write(Conv.default, TurnScript(TurnName("cm-$turn"), listOf(Delta.toolCall(CallId("t$turn"))))) }
        val live = store.pageOf(Conv.default, null, 500)
        assertTrue(live.baseSeq > 0, "retention compacted the journal")

        val stale = store.pageOf(Conv.default, FeedCursor(store.epoch, 1), 500)
        val beyond = store.pageOf(Conv.default, FeedCursor(store.epoch, live.headSeq + 50), 500)

        assertEquals(ConversationFeedWire.RESYNC_BEHIND_HORIZON, stale.resyncReason)
        assertEquals(ConversationFeedWire.RESYNC_AHEAD_OF_HEAD, beyond.resyncReason)
        assertEquals(ConversationFeedWire.MODE_SNAPSHOT, stale.mode)
        val atHorizon = store.pageOf(Conv.default, FeedCursor(store.epoch, live.baseSeq), 500)
        assertEquals(ConversationFeedWire.MODE_REPLAY, atHorizon.mode, "exactly at the horizon still resumes")
        assertTrue(stale.seqs().all { it > live.baseSeq })
    }

    @Test
    fun theHostNeverSynthesizesATerminalForATurnItNeverSawEnd() {
        val dir = directory()
        val first = start(dir)
        val running = first.writerFor(Conv.default, TurnName("cm-1"))
        running.append(Delta.assistant(Text("partial")))
        running.noteInitiatorMissedTail()
        // The host dies here: the turn is never closed.

        val restarted = start(dir)
        val page = restarted.pageOf(Conv.default, null, 100)

        val types = page.payloadTypes().filterNotNull()
        assertTrue("stop_reason" !in types && "error_message" !in types && "loop_error" !in types, "no terminal was invented: $types")
        val closed = page.entries.single { it.kind == ConversationFeedWire.KIND_TURN_CLOSED }
        assertEquals("false", closed.payload?.text("terminal_observed"), "the fact recorded is that no terminal was observed")
        assertEquals("true", closed.payload?.text("host_restarted"))
        val replay = assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1")))
        assertFalse(replay.terminalObserved, "so the legacy path, not the journal, decides what an old client is told")
        assertEquals(1, replay.deltas.size)
    }

    @Test
    fun aRealTerminalThatWentThroughBeforeTheHostDiedStillCountsAsObserved() {
        val dir = directory()
        val first = start(dir)
        val running = first.writerFor(Conv.default, TurnName("cm-1"))
        running.append(Delta.stopReason())
        running.noteInitiatorMissedTail()

        val restarted = start(dir)

        assertTrue(assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1"))).terminalObserved)
    }

    @Test
    fun cumulativeTextSnapshotsCoalesceAndTheFinalTextSurvivesARestart() {
        val dir = directory()
        val first = start(dir, FeedRetention(textFlushEvery = 8))
        val writer = first.writerFor(Conv.default, TurnName("cm-1"))
        (1..200).forEach { writer.append(Delta.assistant(Text("x".repeat(it)))) }
        writer.append(Delta.stopReason())
        writer.close(terminalObserved = true)
        val live = first.pageOf(Conv.default, null, 500)
        assertEquals(1, live.payloadTypes().count { it == "assistant_message" }, "one entry holds the message's latest snapshot")
        assertTrue(Files.size(fileOf(dir)) < 200 * 120L, "the file holds a fraction of the 200 snapshots")

        val restarted = start(dir)
        val text = restarted.pageOf(Conv.default, null, 500).entries
            .filter { it.payload?.text("message_type") == "assistant_message" }
            .single().payload?.text("content")

        assertEquals("x".repeat(200), text)
    }

    @Test
    fun aSupersedingSnapshotKeepsItsOwnGrowingSeqSoACursorThatSawTheOldOneStillReceivesTheNewOne() {
        val store = start(directory())
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        writer.append(Delta.assistant(Text("hel")))
        val cursor = store.pageOf(Conv.default, null, 100).cursor

        writer.append(Delta.assistant(Text("hello")))
        val update = store.pageOf(Conv.default, cursor, 100)

        assertEquals(1, update.entries.size)
        assertEquals("hello", update.entries.single().payload?.text("content"))
        assertTrue(update.entries.single().seq > cursor.seq)
    }

    @Test
    fun anOversizedPayloadBecomesAPointerAndNeverCrossesTheFrameCap() {
        val store = start(directory())
        val big = "z".repeat(ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES + 1)
        val deltas = listOf(Delta.toolReturn(CallId("t1"), Text(big)), Delta.toolCall(CallId("t2")))
        store.write(Conv.default, TurnScript(TurnName("cm-1"), deltas))

        val page = store.pageOf(Conv.default, null, 100)
        val pointed = page.entries.single { it.pointer != null }

        assertNull(pointed.payload)
        assertEquals(ConversationFeedWire.KIND_STREAM_DELTA, pointed.kind)
        assertEquals("tool_return_message", pointed.pointer?.text(ConversationFeedWire.POINTER_MESSAGE_TYPE))
        assertTrue((pointed.pointer?.get(ConversationFeedWire.POINTER_BYTE_LEN)?.jsonPrimitive?.content?.toInt() ?: 0) > big.length)
        assertTrue(Json.encodeToString(com.letta.mobile.data.feed.wire.FeedPage.serializer(), page).length < 1_048_576 / 2)
        assertTrue(store.replayFor(Conv.default, TurnName("cm-1")) == null, "nothing owed: the initiator saw the turn")
    }

    @Test
    fun aPageStaysUnderTheByteBudgetEvenWithManyLargePayloads() {
        val store = start(directory())
        val chunk = "q".repeat(ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES - 1_000)
        store.write(Conv.default, TurnScript(TurnName("cm-1"), (1..6).map { Delta.toolReturn(CallId("t$it"), Text(chunk)) }))

        val pages = store.drainOf(Conv.default, null)

        assertTrue(pages.size >= 2, "six near-maximal payloads cannot fit one page")
        pages.forEach { page ->
            val encoded = Json.encodeToString(com.letta.mobile.data.feed.wire.FeedPage.serializer(), page).toByteArray(Charsets.UTF_8)
            assertTrue(encoded.size < FRAME_CAP_BYTES, "the encoded page (entries, envelopes, metadata) is ${encoded.size} bytes")
        }
        assertEquals(8, pages.sumOf { it.entries.size })
    }

    @Test
    fun retentionBoundsEntriesAndDropsFinishedTurnsOldestFirstButNeverTheRunningOne() {
        val dir = directory()
        val store = start(dir, FeedRetention(maxEntriesPerConversation = 20))
        (1..30).forEach { turn ->
            val deltas = listOf(Delta.toolCall(CallId("a$turn")), Delta.toolReturn(CallId("a$turn")))
            store.write(Conv.default, TurnScript(TurnName("cm-$turn"), deltas))
        }
        val running = store.writerFor(Conv.default, TurnName("cm-live"))
        running.append(Delta.toolCall(CallId("live-1")))

        val page = store.pageOf(Conv.default, null, 500)

        assertTrue(page.entries.size <= 20, "bounded: ${page.entries.size}")
        assertTrue(page.baseSeq > 0)
        assertTrue(page.entries.any { it.turn == "cm-live" }, "the running turn is retained")
        assertFalse(page.entries.any { it.turn == "cm-1" }, "the oldest finished turn went first")
        assertTrue(page.entries.any { it.turn == "cm-30" })
        assertFalse(store.knowsTurn(Conv.default, TurnName("cm-1")))
        val lines = fileOf(dir).readText().lines().filter { it.isNotBlank() }
        assertTrue(lines.size <= 20 + 10, "the file was compacted with the log: ${lines.size} lines")
    }

    @Test
    fun retentionAlsoBoundsBytesAndAnEnormousSingleTurnLosesItsOldestEntriesNotItsNewest() {
        val store = start(directory(), FeedRetention(maxBytesPerConversation = 64 * 1024))
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        val body = "b".repeat(8 * 1024)
        (1..40).forEach { writer.append(Delta.toolReturn(CallId("t$it"), Text(body))) }

        val page = store.pageOf(Conv.default, null, 500)

        assertTrue(page.entries.sumOf { (it.payload ?: it.pointer).toString().length } <= 64 * 1024 + 16 * 1024)
        assertEquals(40L + 1, page.headSeq, "seq never reuses a dropped value")
        assertEquals("t40", page.entries.last().payload?.text("tool_call_id"))
    }

    @Test
    fun theDiskCeilingDeletesLeastRecentlyUsedConversationsAndKeepsOneWithARunningTurn() {
        val dir = directory()
        val store = start(dir, FeedRetention(diskCeilingBytes = 24 * 1024))
        val body = "d".repeat(2 * 1024)
        val running = store.writerFor(Conv.of("conv-running"), TurnName("cm-run"))
        running.append(Delta.toolReturn(CallId("r1"), Text(body)))
        (1..20).forEach { n ->
            val deltas = listOf(Delta.toolReturn(CallId("t$n"), Text(body)), Delta.toolReturn(CallId("u$n"), Text(body)))
            store.write(Conv.of("conv-$n"), TurnScript(TurnName("cm-$n"), deltas))
        }

        val files = dir.listDirectoryEntries("*.jsonl")

        val total = files.sumOf { Files.size(it) }
        assertTrue(total <= 24 * 1024 + 16 * 1024, "directory stays near its ceiling: $total")
        assertTrue(files.size < 21, "some conversations were deleted")
        assertTrue(store.pageOf(Conv.of("conv-running"), null, 50).entries.isNotEmpty(), "the running turn's conversation survived")
        assertTrue(store.pageOf(Conv.of("conv-1"), null, 50).entries.isEmpty(), "the least recently used conversation went first")
        assertTrue(store.pageOf(Conv.of("conv-20"), null, 50).entries.isNotEmpty(), "the most recent one stayed")
    }

    @Test
    fun aCorruptJournalFileIsLoggedAndResetNeverACrash() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        fileOf(dir).writeText("{\"v\":1,\"agent_id\":\"agent-a\"\nthis is not json\n")

        val restarted = start(dir)
        val page = restarted.pageOf(Conv.default, null, 100)

        assertTrue(page.entries.isEmpty(), "the conversation starts over")
        assertEquals(ConversationFeedWire.MODE_SNAPSHOT, page.mode)
        assertFalse(restarted.knowsTurn(Conv.default, TurnName("cm-1")))
        restarted.write(Conv.default, TurnScript(TurnName("cm-2"), listOf(Delta.toolCall(CallId("t2")))))
        assertEquals(3, start(dir).pageOf(Conv.default, null, 100).entries.size, "the reset file accepts new turns and reads back")
    }

    @Test
    fun aJournalWhoseSeqGoesBackwardsIsCorruptNotTrusted() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        val file = fileOf(dir)
        val lines = file.readText().lines().filter { it.isNotBlank() }
        file.writeText((lines + lines.last()).joinToString("\n") + "\n")

        assertTrue(start(dir).pageOf(Conv.default, null, 100).entries.isEmpty())
    }

    @Test
    fun aTornFinalLineFromADiedWriteIsDroppedAndEverythingBeforeItIsKept() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        fileOf(dir).let { it.writeText(it.readText() + "{\"e\":1,\"s\":99,\"k\":\"stream_del") }

        val restarted = start(dir)
        val page = restarted.pageOf(Conv.default, null, 100)

        assertEquals(3, page.entries.size)
        restarted.write(Conv.default, TurnScript(TurnName("cm-2"), listOf(Delta.toolCall(CallId("t2")))))
        assertEquals(6, start(dir).pageOf(Conv.default, null, 100).entries.size, "appending after a torn tail does not corrupt the file")
    }

    @Test
    fun aHostWithAnUnwritableDirectoryKeepsTheJournalInMemoryAndNeverThrows() {
        val blocker = directory().resolve("not-a-dir").also { it.writeText("file") }
        val store = start(blocker.resolve("conversation-feed"))

        store.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1"))), Ending.Terminal))

        assertEquals(3, store.pageOf(Conv.default, null, 100).entries.size)
    }

    @Test
    fun conversationsAndAgentsAreSeparateJournalsAndTheDefaultAliasIsOneKey() {
        val store = start(directory())
        store.write(Conv.of("default"), TurnScript(TurnName("cm-a"), listOf(Delta.toolCall(CallId("a")))))
        val theirs = listOf(Delta.toolCall(CallId("b")), Delta.toolCall(CallId("b2")))
        store.write(Conv.of("default", Ids.otherAgent), TurnScript(TurnName("cm-b"), theirs))

        val alias = JournalKey.of(AgentId("ignored"), ConversationId("conv-default-${Ids.agent}"))
        val viaAliasA = store.page(alias, null, 50)
        val viaBareB = store.pageOf(Conv.of("default", Ids.otherAgent), null, 50)

        assertEquals(3, viaAliasA.entries.size, "conv-default-<agent> is that agent's default conversation")
        assertEquals(4, viaBareB.entries.size)
        assertTrue(viaAliasA.entries.none { it.turn == "cm-b" })
        assertNull(store.ownerOf(ConversationId("default")), "the bare default belongs to no single agent")
    }

    @Test
    fun theJournalRecordsWhichAgentOwnsAConversationAndKeepsItAcrossARestart() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.of("conv-7"), TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))

        assertEquals(Ids.agent.value, first.ownerOf(ConversationId("conv-7"))?.value)
        assertEquals(Ids.agent.value, start(dir).ownerOf(ConversationId("conv-7"))?.value, "read from the file's own header")
        assertNull(start(dir).ownerOf(ConversationId("conv-unknown")))
    }

    @Test
    fun concurrentTurnsOnOneConversationKeepSeqStrictlyIncreasingAndLoseNothing() {
        val store = start(directory())
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(1)
        val tasks = (1..4).map { writer ->
            pool.submit {
                ready.await()
                val turn = store.writerFor(Conv.default, TurnName("cm-$writer"))
                (1..50).forEach { turn.append(Delta.toolCall(CallId("w$writer-$it"))) }
                turn.close(terminalObserved = false)
            }
        }
        ready.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()

        val seqs = store.drainOf(Conv.default, null).flatMap { it.seqs() }

        assertEquals(4 * 52, seqs.size)
        assertContentEquals(seqs.sorted(), seqs, "wire order is seq order")
        assertEquals(seqs.size, seqs.toSet().size)
        assertEquals((1L..seqs.size).toList(), seqs)
    }

    @Test
    fun theEpochOnlyGrowsEvenWhenTheClockGoesBackwards() {
        val dir = directory()
        val first = FeedJournalStore(dir, FeedRetention()) { 5_000_000L }
        val second = FeedJournalStore(dir, FeedRetention()) { 1L }

        assertTrue(second.epoch > first.epoch)
    }

    private companion object {
        const val FRAME_CAP_BYTES = 1_048_576
    }
}
