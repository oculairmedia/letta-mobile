package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The journal's durability guarantees under pressure: eviction and degraded files never reissue a seq, a log is pinned for
 * its writer, compaction keeps turn state, recovery order, directory sync, owner-only files and the bounds on ids, terminals
 * and retention arithmetic.
 */
class FeedJournalDurabilityTest : FeedJournalTestBase() {
    @Test
    fun aConversationEvictedFromDiskNeverReissuesASeqSoNoCursorEverSkipsNewEntries() {
        val dir = directory()
        val store = start(dir, FeedRetention(diskCeilingBytes = 12 * 1024))
        val body = Text("e".repeat(2 * 1024))
        val evicted = Conv.of("conv-evicted")
        store.write(evicted, TurnScript(TurnName("cm-old"), listOf(Delta.toolCall(CallId("a")), Delta.toolCall(CallId("b")))))
        val applied = store.pageOf(evicted, null, 100)
        val caughtUp = applied.cursor
        (1..12).forEach { n ->
            store.write(Conv.of("conv-other-$n"), TurnScript(TurnName("cm-$n"), listOf(Delta.toolReturn(CallId("t$n"), body))))
        }
        assertTrue(store.pageOf(evicted, null, 100).entries.isEmpty(), "the conversation was evicted from disk")

        store.write(evicted, TurnScript(TurnName("cm-new"), (1..6).map { Delta.toolCall(CallId("n$it")) }))
        val resumedFromHead = store.pageOf(evicted, caughtUp, 100)
        val resumedFromInside = store.pageOf(evicted, FeedCursor(store.epoch, 2), 100)

        assertEquals(ConversationFeedWire.MODE_REPLAY, resumedFromHead.mode)
        assertEquals(8, resumedFromHead.entries.size, "turn_open + 6 calls + turn_closed: every new entry, none skipped")
        assertEquals(caughtUp.seq + 1, resumedFromHead.seqs().first(), "the new generation continues right after the old one")
        assertEquals(resumedFromHead.seqs(), (caughtUp.seq + 1..caughtUp.seq + 8).toList(), "contiguous")
        assertEquals(ConversationFeedWire.MODE_SNAPSHOT, resumedFromInside.mode, "a cursor inside the evicted generation must resync")
        assertEquals(ConversationFeedWire.RESYNC_BEHIND_HORIZON, resumedFromInside.resyncReason)
    }

    @Test
    fun aTurnWhoseOpenWasCompactedAwayIsStillRecoveredAndKnownAfterARestart() {
        val dir = directory()
        val first = start(dir, FeedRetention(maxEntriesPerConversation = 4))
        val writer = first.writerFor(Conv.default, TurnName("cm-1"))
        (1..4).forEach { writer.append(Delta.toolCall(CallId("t$it"))) }
        writer.noteInitiatorMissedTail()
        assertTrue(first.pageOf(Conv.default, null, 100).kinds().none { it == "turn_open" }, "retention dropped the turn_open")

        val restarted = start(dir, FeedRetention(maxEntriesPerConversation = 4))

        assertTrue(restarted.knowsTurn(Conv.default, TurnName("cm-1")), "a re-sent input is still a duplicate")
        val closed = restarted.pageOf(Conv.default, null, 100).entries.filter { it.kind == ConversationFeedWire.KIND_TURN_CLOSED }
        assertEquals(1, closed.size, "the unfinished turn was closed by the recovery")
        assertEquals("true", closed.single().payload?.text("host_restarted"))
        assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1")), "and its initiator is owed the tail")
    }

    @Test
    fun theMissedMarkerIsWrittenBeforeTheRecoveryCloseSoACrashBetweenThemLosesNothing() {
        val dir = directory()
        val first = start(dir)
        first.writerFor(Conv.default, TurnName("cm-1")).append(Delta.toolCall(CallId("t1")))

        val restarted = start(dir)
        restarted.pageOf(Conv.default, null, 100)

        val lines = fileOf(dir).readText().lines().filter { it.isNotBlank() }
        val missed = lines.indexOfFirst { it.contains("\"f\":\"missed\"") }
        val closed = lines.indexOfFirst { it.contains("turn_closed") }
        assertTrue(missed >= 0 && closed >= 0, "both records exist: $lines")
        assertTrue(missed < closed, "the missed marker precedes the close (a crash after the close can no longer lose it)")
    }

    @Test
    fun recordingADetachmentAfterTheTurnFinishedNeverReopensIt() {
        val dir = directory()
        val store = start(dir)
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        writer.play(listOf(Delta.toolCall(CallId("t1")), Delta.stopReason()), Ending.Terminal)

        writer.noteInitiatorMissedTail()

        assertNotNull(store.replayFor(Conv.default, TurnName("cm-1")), "the finished turn is owed to its initiator, and is still finished")
        assertEquals(
            listOf("turn_open", "stream_delta", "stream_delta", "turn_closed"),
            store.pageOf(Conv.default, null, 100).kinds(),
        )
        val closedEntries = start(dir).pageOf(Conv.default, null, 100).entries.count { it.kind == ConversationFeedWire.KIND_TURN_CLOSED }
        assertEquals(1, closedEntries, "a restart finds nothing left running to close again")
    }

    @Test
    fun aTerminalLargerThanTheInlineCapIsKeptInlineAndReplayedNeverLostAsAPointer() {
        val dir = directory()
        val first = start(dir)
        val huge = "x".repeat(ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES + 10)
        val terminal = Delta("""{"message_type":"error_message","message":"$huge","status":"error"}""")
        val writer = first.writerFor(Conv.default, TurnName("cm-1"))
        writer.play(listOf(Delta.toolCall(CallId("t1")), terminal), Ending.Terminal)
        writer.noteInitiatorMissedTail()

        val restarted = start(dir)
        val replay = assertNotNull(restarted.replayFor(Conv.default, TurnName("cm-1")))

        assertTrue(replay.terminalObserved)
        val delivered = replay.deltas.map { it.take(60) }
        assertTrue(replay.deltas.any { it.contains("error_message") }, "the terminal is delivered on replay: $delivered")
        assertTrue(replay.deltas.all { it.length < ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES }, "compacted, so it fits a frame")
        val pointed = restarted.pageOf(Conv.default, null, 100).entries.filter { it.pointer != null }
        assertTrue(pointed.none { it.pointer?.text("message_type") == "error_message" }, "no terminal is stored as a pointer")
    }

    @Test
    fun aReplayIsServedEvenWhenLoadingItsLogTrimsItBecauseOnlyRunningTurnsAreHeldInMemory() {
        val dir = directory()
        val first = start(dir)
        val theirs = Conv.of("conv-b")
        first.writerFor(theirs, TurnName("cm-b")).also { writer ->
            writer.play(listOf(Delta.toolCall(CallId("t1")), Delta.stopReason()), Ending.Terminal)
            writer.noteInitiatorMissedTail()
        }
        val restarted = start(dir, FeedRetention(maxLoadedLogs = 1))
        restarted.writerFor(Conv.of("conv-a"), TurnName("cm-a")).append(Delta.toolCall(CallId("busy")))

        val replay = restarted.replayFor(theirs, TurnName("cm-b"))

        assertNotNull(replay, "the replay reads the log it just loaded, not a cache entry the trim may have dropped")
        assertTrue(replay.deltas.any { it.contains("stop_reason") })
    }

    @Test
    fun aTornTailIsRepairedBeforeTheRecoveryAppendsSoTheNextLoadStillReadsEverything() {
        val dir = directory()
        val first = start(dir)
        first.writerFor(Conv.default, TurnName("cm-1")).append(Delta.toolCall(CallId("t1")))
        fileOf(dir).let { it.writeText(it.readText() + "{\"e\":1,\"s\":99,\"k\":\"stream_del") }

        start(dir).pageOf(Conv.default, null, 100)
        val third = start(dir).pageOf(Conv.default, null, 100)

        val kinds = third.kinds()
        assertTrue(kinds.contains(ConversationFeedWire.KIND_TURN_CLOSED), "the recovery's records survived: $kinds")
        assertTrue(third.entries.any { it.kind == ConversationFeedWire.KIND_STREAM_DELTA }, "and so did the turn's own entry")
    }

    @Test
    fun aLongTurnIdIsBoundedSoAPageStaysUnderTheFrameCap() {
        val store = start(directory())
        val longId = "turn-" + "y".repeat(20_000)
        val writer = store.writerFor(Conv.default, TurnName(longId))
        (1..100).forEach { writer.append(Delta.toolCall(CallId("t$it"))) }
        writer.close(false)

        val page = store.pageOf(Conv.default, null, 500)

        val encoded = Json.encodeToString(com.letta.mobile.data.feed.wire.FeedPage.serializer(), page)
        assertTrue(encoded.toByteArray().size < 1_048_576, "page of ${encoded.length} bytes must fit one 1 MiB frame")
        assertTrue(page.entries.all { it.turn.length <= 256 })
        assertTrue(store.knowsTurn(Conv.default, TurnName(longId)), "the same long id still names the same turn")
    }

    @Test
    fun aDegradedJournalReloadedFromItsShortDiskFileNeverReissuesTheSeqsMemoryIssued() {
        val dir = directory()
        // The file is created, then every append fails: disk stops at the first entries while memory goes on issuing.
        val failing = FileOps(append = { _, _ -> throw java.io.IOException("disk full") })
        val store = FeedJournalStore(dir, FeedRetention(maxLoadedLogs = 1), { nextClock() }, failing)
        val degraded = Conv.default
        val writer = store.writerFor(degraded, TurnName("cm-1"))
        (1..6).forEach { writer.append(Delta.toolCall(CallId("t$it"))) }
        writer.close(false)
        val caughtUp = store.pageOf(degraded, null, 100).cursor
        assertTrue(caughtUp.seq >= 8, "memory issued turn_open + 6 calls + turn_closed: ${caughtUp.seq}")
        // Another conversation's turn trims the idle degraded log from memory; only the short file remains.
        store.write(Conv.of("conv-other"), TurnScript(TurnName("cm-2"), listOf(Delta.toolCall(CallId("x")))))

        store.write(degraded, TurnScript(TurnName("cm-next"), listOf(Delta.toolCall(CallId("n1")))))
        val seqs = store.pageOf(degraded, caughtUp, 100).seqs()

        assertTrue(seqs.isNotEmpty() && seqs.all { it > caughtUp.seq }, "no seq at or below the one a viewer holds is reissued: $seqs")
    }

    @Test
    fun aLogLoadedForAWriterIsNotEvictedBeforeItsTurnOpensSoItIsNeverClosedAsAnOrphan() {
        val dir = directory()
        val first = start(dir)
        first.write(Conv.of("conv-b"), TurnScript(TurnName("cm-old"), listOf(Delta.toolCall(CallId("b0")))))
        val store = start(dir, FeedRetention(maxLoadedLogs = 1))
        val busy = store.writerFor(Conv.of("conv-a"), TurnName("cm-a"))
        busy.append(Delta.toolCall(CallId("a1")))

        val live = store.writerFor(Conv.of("conv-b"), TurnName("cm-live"))
        live.append(Delta.toolCall(CallId("b1")))
        live.append(Delta.toolCall(CallId("b2")))

        val page = store.pageOf(Conv.of("conv-b"), null, 100)
        val closedLive = page.entries.filter { it.kind == ConversationFeedWire.KIND_TURN_CLOSED && it.turn == "cm-live" }
        assertTrue(closedLive.isEmpty(), "the live turn was not closed as a restart orphan: ${page.kinds()}")
        assertEquals(1, page.entries.count { it.turn == "cm-live" && it.kind == ConversationFeedWire.KIND_TURN_OPEN })
    }

    @Test
    fun obsoleteSnapshotRecordsOfARunningTurnAreCompactedOnDiskWhileItStreams() {
        val dir = directory()
        val store = start(dir, FeedRetention(textFlushEvery = 2))
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        val text = "w".repeat(40_000)
        (1..400).forEach { writer.append(Delta.assistant(Text(text.take(100 + it * 90)))) }

        val onDisk = Files.size(fileOf(dir))

        assertTrue(onDisk < 400 * 40_000L / 8, "the file holds a few live snapshots, not every superseded one: $onDisk bytes")
        assertEquals(1, store.pageOf(Conv.default, null, 100).payloadTypes().count { it == "assistant_message" })
    }

    @Test
    fun aFinishedTurnWhoseEntriesInterleaveWithAnotherTurnsKeepsItsStateWhileAnyEntryRemains() {
        val dir = directory()
        val store = start(dir, FeedRetention(maxEntriesPerConversation = 6))
        val a = store.writerFor(Conv.default, TurnName("cm-a"))
        val b = store.writerFor(Conv.default, TurnName("cm-b"))
        a.append(Delta.toolCall(CallId("a1")))
        b.append(Delta.toolCall(CallId("b1")))
        a.append(Delta.stopReason())
        a.noteInitiatorMissedTail()
        a.close(true)
        b.append(Delta.toolCall(CallId("b2")))
        b.append(Delta.toolCall(CallId("b3")))

        val kept = store.pageOf(Conv.default, null, 100).entries.any { it.turn == "cm-a" }
        if (kept) {
            assertTrue(store.knowsTurn(Conv.default, TurnName("cm-a")), "turn A still has entries, so it is still a known turn")
            assertNotNull(store.replayFor(Conv.default, TurnName("cm-a")), "and its owed tail is still offered")
        }
        assertTrue(kept, "the scenario keeps some of A's entries behind B's")
    }

    @Test
    fun retentionTargetsNeverOverflowForHugeLimits() {
        val dir = directory()
        val store = start(dir, FeedRetention(maxEntriesPerConversation = 4, maxBytesPerConversation = Long.MAX_VALUE / 2))
        val writer = store.writerFor(Conv.default, TurnName("cm-1"))
        (1..9).forEach { writer.append(Delta.toolCall(CallId("t$it"))) }

        val page = store.pageOf(Conv.default, null, 100)

        assertTrue(page.entries.isNotEmpty() && page.entries.size <= 4, "compacted by count, no crash: ${page.entries.size}")
    }

    @Test
    fun aHeaderOnlyFileWithoutANewlineIsRepairedBeforeAnythingIsAppended() {
        val dir = directory()
        start(dir).write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        val file = fileOf(dir)
        file.writeText(file.readText().lines().first())

        val restarted = start(dir)
        restarted.write(Conv.default, TurnScript(TurnName("cm-2"), listOf(Delta.toolCall(CallId("t2")))))
        val third = start(dir).pageOf(Conv.default, null, 100)

        assertTrue(third.entries.any { it.turn == "cm-2" }, "the records written after the crash survive a reload: ${third.kinds()}")
    }

    @Test
    fun aRawTurnIdThatLooksLikeAGeneratedOneNeverCollidesWithTheLongIdItWasDerivedFrom() {
        val store = start(directory())
        val long = "x".repeat(129)
        val lookalike = journalTurn(long).value
        store.write(Conv.default, TurnScript(TurnName(long), listOf(Delta.toolCall(CallId("a")))))

        assertTrue(store.knowsTurn(Conv.default, TurnName(long)))
        assertFalse(store.knowsTurn(Conv.default, TurnName(lookalike)), "a raw id equal to the generated hash id is a different turn")
        assertNotEquals(journalTurn(long), journalTurn(lookalike))
        assertEquals("short-id", journalTurn("short-id").value, "ordinary ids are unchanged")
    }

    @Test
    fun aTerminalWithHugeOptionalFieldsIsBoundedToTheInlineCapKeepingItsTypeAndReason() {
        val store = start(directory())
        val huge = "r".repeat(ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES + 10)
        val diagnostics = (1..400).joinToString(",") { "\"" + "d".repeat(1_000) + "\"" }
        val terminal = Delta("""{"message_type":"error_message","status":"error","run_id":"$huge","diagnostics":[$diagnostics]}""")
        store.write(Conv.default, TurnScript(TurnName("cm-1"), listOf(terminal), Ending.Terminal))

        val stored = store.pageOf(Conv.default, null, 100).entries.single { it.payload?.text("message_type") == "error_message" }

        val size = stored.payload.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(size <= ConversationFeedWire.MAX_INLINE_PAYLOAD_BYTES, "the stored terminal is $size bytes")
        assertEquals("error", stored.payload?.text("status"), "what decides how the turn ended is kept")
    }

    @Test
    fun aHostWhoseEpochCouldNotBePersistedDoesNotServeItsFeed() {
        val dir = directory()
        // A directory where the epoch file must go makes the write fail.
        dir.resolve("epoch").createDirectories().resolve("blocker").writeText("x")

        val store = start(dir)

        assertFalse(store.epochPersisted, "the epoch could not be written")
    }

    @Test
    fun journalFilesAreReadableByTheHostsUserAlone() {
        val dir = directory()
        val store = start(dir, FeedRetention(maxEntriesPerConversation = 8))
        (1..6).forEach { turn -> store.write(Conv.default, TurnScript(TurnName("cm-$turn"), listOf(Delta.toolCall(CallId("t$turn"))))) }

        val file = fileOf(dir)

        assertTrue(isOwnerOnly(file), "after a compaction rewrite")
        val fresh = directory()
        start(fresh).write(Conv.default, TurnScript(TurnName("cm-1"), listOf(Delta.toolCall(CallId("t1")))))
        assertTrue(isOwnerOnly(fileOf(fresh)), "a new file")
    }

    /**
     * Whether only the file's owner can access it, by the permission view the filesystem supports: `rw-------` on POSIX, an
     * ACL naming only the owner otherwise. A filesystem with neither view cannot hold an owner-only file at all.
     */
    private fun isOwnerOnly(file: Path): Boolean {
        Files.getFileAttributeView(file, PosixFileAttributeView::class.java)?.let {
            return PosixFilePermissions.toString(it.readAttributes().permissions()) == "rw-------"
        }
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        assumeTrue("neither a POSIX nor an ACL permission view on this filesystem", acl != null)
        val owner = Files.getOwner(file)
        return acl!!.acl.all { entry -> entry.principal() == owner }
    }
}
