package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.*
import com.letta.mobile.data.timeline.snapshot.*
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomTimelineBoundedStoreTest {
    private lateinit var db: TimelineLedgerDatabase
    private lateinit var store: RoomTimelineBoundedStore
    private val scope = TimelineScope("backend", "conversation", "agent")
    private val codec = object : RoomTimelineCheckpointCodec {
        override fun encode(checkpoint: TimelineDurableCheckpoint): ByteArray {
            check(checkpoint.continuation == null)
            return "${checkpoint.revision}:${checkpoint.hasMore}".encodeToByteArray()
        }
        override fun decode(bytes: ByteArray): TimelineDurableCheckpoint {
            val parts = bytes.decodeToString().split(':')
            return TimelineDurableCheckpoint(parts[0].toLong(), null, parts[1].toBooleanStrict())
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimelineLedgerDatabase::class.java).build()
        store = RoomTimelineBoundedStore(db, codec)
    }
    @After fun teardown() { db.close() }

    /** Real writer and fresh engine, with no surviving in-memory image after Room reopens. */
    @Test fun largeInlineImageSurvivesCanonicalColdReopenByteIdentically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "image-cold-${java.util.UUID.randomUUID()}.db"
        db.close()
        db = Room.databaseBuilder(context, TimelineLedgerDatabase::class.java, name).build()
        store = RoomTimelineBoundedStore(db)
        try {
            val bytes = ByteArray(105_868) { (it % 251).toByte() }
            val encoded = Base64.getEncoder().encodeToString(bytes)
            val source = StoredTimelineEvent(1.0, "otid", serverId = "image", messageType = "USER",
                dateIso = "2026-01-01T00:00:00Z", attachments = listOf(
                    StoredImageAttachmentPointer("image/png", bytes.size.toLong(), thumbnailBase64 = encoded),
                )).toConfirmedTimelineEvent()
            store.transaction(scope) {
                assertTrue(TimelineExactCanonicalWriter(scope, 100_000).mergeEvent(this, source))
                val row = metadata(TimelineReadPosition.Tail, 1).rows.single()
                val payload = body(row.body, 0, 2048).decodeToString()
                assertFalse(payload.contains(encoded))
                assertTrue(payload.length < 2048)
                assertTrue(payload.contains("bodyReference"))
                nextRevision()
            }
            store.transaction(scope) {
                assertFalse(TimelineExactCanonicalWriter(scope, 100_000).mergeEvent(this, source))
            }
            db.close()
            db = Room.databaseBuilder(context, TimelineLedgerDatabase::class.java, name).build()
            store = RoomTimelineBoundedStore(db)
            val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
            val selection = (engine.open(scope) as TimelineEngineOpen.Opened).selection
            val page = engine.preparePage(selection, TimelineReadPosition.Tail, 1, null)
            val presentation = page.records.single().preparedPresentation as TimelineSettledPresentation.Render
            val ui = com.letta.mobile.data.chat.projection.timelineEventToUiMessage(presentation.event, null)!!
            val rendered = Base64.getDecoder().decode(ui.attachments.single().base64)
            assertArrayEquals(bytes, rendered)
            assertEquals(checksum(bytes), checksum(rendered))
            store.read(scope) {
                val metadata = metadata(TimelineReadPosition.Tail, 1).rows.single()
                assertTrue(metadata.body.encodedBytes < 2048)
                val stored = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(),
                    body(metadata.body, 0, 2048).decodeToString())
                val reference = stored.attachments.single().bodyReference!!
                assertEquals(checksum(bytes), reference.sha256)
                val imageBodies = this as TimelineImageBodyReader
                val restored = stored.toConfirmedTimelineEventWithImageBodies(imageBodies)
                val actual = Base64.getDecoder().decode(restored.attachments.single().base64)
                assertEquals(checksum(bytes), checksum(actual))
                assertArrayEquals(bytes, actual)
                assertNull(imageBodies.resolveImage(reference.copy(sha256 = "0".repeat(64))))
                assertNull(imageBodies.resolveImage(reference.copy(decodedBytes = reference.decodedBytes + 1)))
                for (invalid in listOf(
                    reference.copy(sha256 = "0".repeat(64)),
                    reference.copy(decodedBytes = reference.decodedBytes + 1),
                    reference.copy(provenance = "unknown"),
                )) {
                    val unavailable = stored.copy(attachments = listOf(stored.attachments.single().copy(bodyReference = invalid)))
                        .toConfirmedTimelineEventWithImageBodies(imageBodies).attachments.single()
                    assertEquals("", unavailable.base64)
                    assertEquals(bytes.size.toLong(), unavailable.storedByteSize)
                }
                val pointer = "image-sha256:${reference.sha256}"
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE ledger_chunk SET payload = ? WHERE scope = ? AND pointer = ? AND ordinal = 0",
                    arrayOf(byteArrayOf(9), ledgerScopeKey(scope), pointer),
                )
                assertNull(imageBodies.resolveImage(reference))
                assertEquals("", stored.toConfirmedTimelineEventWithImageBodies(imageBodies).attachments.single().base64)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun cancelledImageTransactionLeavesNoReferenceTarget() = runBlocking {
        var reference: StoredImageBodyReference? = null
        try {
            store.transaction(scope) {
                reference = (this as TimelineImageBodyWriter).persistImage(Base64.getEncoder().encodeToString(ByteArray(20000)))
                nextRevision()
                throw CancellationException("abort image and event")
            }
        } catch (_: CancellationException) { }
        store.read(scope) {
            assertNull((this as TimelineImageBodyReader).resolveImage(reference!!))
            assertEquals(0L, checkpoint().revision)
        }
    }

    @Test fun pendingSaveAndDeliveryChangesAllocateExactlyOneRevision() = runBlocking {
        val pending = CanonicalPendingLocalStore(store)
        val record = CanonicalPendingLocalStore.Record("send-1", "hello", emptyList(), "2026-09-08T21:00:00Z")
        pending.save(scope, record)
        assertEquals(listOf(record), pending.load(scope))
        assertEquals(1L, store.read(scope) { checkpoint().revision })
        pending.save(scope, record)
        pending.mark(scope, "absent", CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(1L, store.read(scope) { checkpoint().revision })
        pending.mark(scope, record.otid, CanonicalPendingLocalStore.Delivery.Sent)
        assertEquals(2L, store.read(scope) { checkpoint().revision })
        pending.mark(scope, record.otid, CanonicalPendingLocalStore.Delivery.Sent)
        assertEquals(2L, store.read(scope) { checkpoint().revision })
        pending.mark(scope, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(3L, store.read(scope) { checkpoint().revision })
        val reopened = CanonicalPendingLocalStore(RoomTimelineBoundedStore(db, codec))
        assertEquals(listOf(record.copy(delivery = CanonicalPendingLocalStore.Delivery.Failed)), reopened.load(scope))
    }

    @Test fun replayedEchoRemovalAndToolSweepCommitRevisions() = runBlocking {
        val pending = CanonicalPendingLocalStore(store)
        val local = CanonicalPendingLocalStore.Record("echo-local", "hello", emptyList(), "2026-09-08T21:00:00Z")
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val echo = TimelineRemoteRecord(TimelineMessageId("echo-server"), com.letta.mobile.data.model.UserMessage(
            id = "echo-server", contentRaw = kotlinx.serialization.json.JsonPrimitive("hello"), date = local.sentAt, otid = local.otid,
        ), 0)
        pending.save(scope, local)
        store.transaction(scope) { assertTrue(writer.merge(this, echo)); nextRevision() }
        assertTrue(pending.load(scope).isEmpty())
        pending.save(scope, local)
        store.transaction(scope) {
            assertTrue("Replay removing pending must report mutation", writer.merge(this, echo))
            nextRevision()
        }
        assertEquals(4L, store.read(scope) { checkpoint().revision })
        assertTrue(pending.load(scope).isEmpty())
        store.transaction(scope) { assertFalse(writer.merge(this, echo)) }
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = (engine.open(scope) as TimelineEngineOpen.Opened).selection
        assertEquals(1L, engine.advanceToolSweep(selection))
        assertEquals(5L, store.read(scope) { checkpoint().revision })
        assertEquals(5L, engine.publication.value.durableRevision)
        assertEquals(0, engine.settleToolSweep(selection, 1))
        assertEquals(5L, store.read(scope) { checkpoint().revision })
    }

    @Test fun tiedKeysUseKotlinUtf16OrderAndSqlCaps() = runBlocking {
        val ids = (0..256).map { "id-$it" } + listOf("\uD83D\uDE00", "\uE000", "\uD800", "a\u0000b")
        store.transaction(scope) {
            ids.forEach { put(TimelineStoredRecord(TimelinePageKey(Long.MAX_VALUE, TimelineMessageId(it)), "test", byteArrayOf(1))) }
            nextRevision()
        }
        assertEquals(128, db.ledger().tail(ledgerScopeKey(scope), Int.MAX_VALUE).size)
        assertTrue(db.ledger().tail(ledgerScopeKey(scope), -1).isEmpty())
        store.read(scope) {
            val found = mutableListOf<String>()
            var position: TimelineReadPosition = TimelineReadPosition.Tail
            do {
                val page = metadata(position, 32)
                assertEquals(1L, page.revision)
                assertTrue(page.rows.all { it.revision == 1L })
                found.addAll(0, page.rows.map { it.key.identity.value })
                position = page.older?.let { TimelineReadPosition.Before(it) } ?: break
            } while (true)
            assertEquals(ids.sorted(), found)
            assertEquals(TimelinePageKey(Long.MAX_VALUE, TimelineMessageId("\uD800")), locate(TimelineMessageId("\uD800")))
        }
        store.read(scope.copy(agentId = "other")) { assertTrue(metadata(TimelineReadPosition.Tail, 32).rows.isEmpty()) }
    }

    @Test fun exactLargeBodiesNeverEnterMetadataAndRemainAfterRelease() = runBlocking {
        for (mib in listOf(1, 2, 4, 8)) {
            val bytes = ByteArray(mib * 1024 * 1024) { (it % 251).toByte() }
            store.transaction(scope) {
                put(TimelineStoredRecord(TimelinePageKey(mib.toLong(), TimelineMessageId("body-$mib")), "opaque", bytes))
                nextRevision()
            }
            val pointer = store.read(scope) { metadata(TimelineReadPosition.Tail, 1).rows.single().body }
            assertEquals(bytes.size.toLong(), pointer.encodedBytes)
            store.read(scope) {
                var at = 0
                while (at < bytes.size) {
                    val chunk = body(pointer, at.toLong(), 65536)
                    assertArrayEquals(bytes.copyOfRange(at, minOf(bytes.size, at + 65536)), chunk)
                    at += chunk.size
                }
                assertTrue(body(pointer, bytes.size.toLong(), 65536).isEmpty())
                assertArrayEquals(bytes.copyOfRange(65530, 65630), body(pointer, 65530, 100))
            }
            store = RoomTimelineBoundedStore(db, codec)
            store.read(scope) { assertNotNull(locate(TimelineMessageId("body-$mib"))) }
        }
    }

    @Test fun atomicRevisionRollbackEvidenceAndEscapedSnapshot() = runBlocking {
        var escaped: TimelineStoreReader? = null
        store.transaction(scope) {
            putEvidence("exact\u0000key", byteArrayOf(1, 2, 3))
            cursor(null, false)
            assertEquals(1L, nextRevision())
            assertArrayEquals(byteArrayOf(1, 2, 3), evidence("exact\u0000key", 3))
        }
        try {
            store.transaction(scope) {
                putEvidence("exact\u0000key", byteArrayOf(9))
                nextRevision()
                throw CancellationException("cancel before commit")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        store.read(scope) {
            escaped = this
            assertEquals(TimelineDurableCheckpoint(1, null, false), checkpoint())
            assertArrayEquals(byteArrayOf(1, 2, 3), evidence("exact\u0000key", 3))
            try { evidence("exact\u0000key", 2); fail("Must not truncate") } catch (_: IllegalStateException) { }
        }
        try { escaped!!.checkpoint(); fail("Escaped snapshot") } catch (_: IllegalStateException) { }
        try {
            store.transaction(scope) { putEvidence("uncommitted", byteArrayOf(4)) }
            fail("Missing revision")
        } catch (_: IllegalStateException) { }
        store.read(scope) { assertNull(evidence("uncommitted", 1)) }
    }

    @Test fun revisionStampTouchesOnlyWrittenIdentities() = runBlocking {
        store.transaction(scope) {
            put(TimelineStoredRecord(TimelinePageKey(1, TimelineMessageId("kept")), "test", byteArrayOf(1)))
            put(TimelineStoredRecord(TimelinePageKey(2, TimelineMessageId("other")), "test", byteArrayOf(2)))
            nextRevision()
        }
        store.transaction(scope) {
            put(TimelineStoredRecord(TimelinePageKey(3, TimelineMessageId("new")), "test", byteArrayOf(3)))
            nextRevision()
        }
        store.read(scope) {
            val page = metadata(TimelineReadPosition.Tail, 3)
            val revisions = page.rows.associate { it.key.identity.value to it.revision }
            assertEquals(1L, revisions.getValue("kept"))
            assertEquals(1L, revisions.getValue("other"))
            assertEquals(2L, revisions.getValue("new"))
        }
    }

    @Test fun corruptChunksAndForeignPointersFailWithoutDestroyingGoodRows() = runBlocking {
        store.transaction(scope) {
            put(TimelineStoredRecord(TimelinePageKey(0, TimelineMessageId("good")), "test", byteArrayOf(1, 2, 3)))
            nextRevision()
        }
        val pointer = store.read(scope) { metadata(TimelineReadPosition.Tail, 1).rows.single().body }
        try {
            store.read(scope.copy(conversationId = "foreign")) { body(pointer, 0, 10) }
            fail("Foreign pointer")
        } catch (_: IllegalStateException) { }
        db.openHelper.writableDatabase.execSQL("UPDATE ledger_chunk SET payload = X'030201'")
        try { store.read(scope) { body(pointer, 0, 3) }; fail("Checksum") } catch (_: IllegalStateException) { }
        store.read(scope) { assertNotNull(locate(TimelineMessageId("good"))) }
    }
}
