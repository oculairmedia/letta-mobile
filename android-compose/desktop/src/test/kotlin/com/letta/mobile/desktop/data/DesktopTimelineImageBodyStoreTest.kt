package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineEngine
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineEngineOpen
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineExactCanonicalWriter
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineSettledPresentation
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineImageBodies
import com.letta.mobile.data.timeline.snapshot.StoredImageAttachmentPointer
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyReader
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEventWithImageBodies
import com.letta.mobile.data.timeline.snapshot.toTimelineWithImageBodies
import com.letta.mobile.data.timeline.snapshot.withImageBodies
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Images over the 16 KB inline thumbnail budget must survive a restart on Desktop, in both the
 * canonical ledger and the legacy whole-envelope snapshot store.
 */
class DesktopTimelineImageBodyStoreTest {
    private val scope = TimelineScope("backend", "conversation", "agent")
    private val root: Path = Files.createTempDirectory("timeline-image-bodies")
    private val codec = object : DesktopTimelineCheckpointCodec {
        override fun encode(value: TimelineDurableCheckpoint): ByteArray {
            require(value.continuation == null)
            return "${value.revision}:${value.hasMore}".toByteArray()
        }
        override fun decode(bytes: ByteArray): TimelineDurableCheckpoint {
            val parts = bytes.toString(Charsets.UTF_8).split(':')
            return TimelineDurableCheckpoint(parts[0].toLong(), null, parts[1].toBooleanStrict())
        }
    }

    @AfterTest
    fun tearDown() {
        DesktopTimelineImageBodies.deleteDirectory(root)
    }

    private fun bytes(size: Int, seed: Int = 0) = ByteArray(size) { ((it + seed) % 251).toByte() }
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun imageEvent(serverId: String, position: Double, vararg images: ByteArray): TimelineEvent.Confirmed =
        StoredTimelineEvent(
            position = position,
            otid = "otid-$serverId",
            serverId = serverId,
            messageType = "USER",
            dateIso = "2026-01-01T00:00:00Z",
            attachments = images.map {
                StoredImageAttachmentPointer("image/png", it.size.toLong(), thumbnailBase64 = encode(it))
            },
        ).toConfirmedTimelineEvent()

    private fun canonicalStore() = DesktopTimelineBoundedStore(root.resolve("ledger"), codec)

    private fun canonicalImageFiles(): List<Path> {
        val scopes = root.resolve("ledger")
        if (!Files.isDirectory(scopes)) return emptyList()
        return Files.walk(scopes).use { paths ->
            paths.filter { it.parent.fileName.toString() == "images" && Files.isRegularFile(it) }.toList()
        }
    }

    // ---- canonical ledger ----

    @Test
    fun largeImageSurvivesCanonicalWriterAndEngineReopen() = runTest {
        val image = bytes(105_868)
        val encoded = encode(image)
        assertTrue(encoded.length > 16_384)
        val source = imageEvent("image", 1.0, image)
        canonicalStore().transaction(scope) {
            assertTrue(this is TimelineImageBodyWriter)
            assertTrue(TimelineExactCanonicalWriter(scope, 100_000).mergeEvent(this, source))
            nextRevision()
        }
        // Merging the same event again is a no-op: the body is deduplicated, the record unchanged.
        canonicalStore().transaction(scope) {
            assertFalse(TimelineExactCanonicalWriter(scope, 100_000).mergeEvent(this, source))
        }
        assertEquals(1, canonicalImageFiles().size)

        val reopened = canonicalStore()
        reopened.read(scope) {
            val row = metadata(TimelineReadPosition.Tail, 1).rows.single()
            assertTrue(row.body.encodedBytes < 2048)
            val payload = body(row.body, 0, 2048).decodeToString()
            assertFalse(payload.contains(encoded))
            assertTrue(payload.contains("bodyReference"))
        }
        val engine = CanonicalTimelineEngine(reopened, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = (engine.open(scope) as TimelineEngineOpen.Opened).selection
        val page = engine.preparePage(selection, TimelineReadPosition.Tail, 1, null)
        val presentation = page.records.single().preparedPresentation as TimelineSettledPresentation.Render
        assertContentEquals(image, Base64.getDecoder().decode(presentation.event.attachments.single().base64))
    }

    @Test
    fun canonicalDedupesBodiesAndRollbackLeavesNoBody() = runTest {
        val encoded = encode(bytes(40_000))
        var first: StoredImageBodyReference? = null
        canonicalStore().transaction(scope) {
            val writer = this as TimelineImageBodyWriter
            first = writer.persistImage(encoded)
            // Staged bodies already resolve inside their own transaction.
            assertEquals(encoded, writer.resolveImage(first!!))
            assertEquals(first, writer.persistImage(encoded))
        }
        canonicalStore().transaction(scope) {
            assertEquals(first, (this as TimelineImageBodyWriter).persistImage(encoded))
        }
        assertEquals(1, canonicalImageFiles().size)

        val other = encode(bytes(30_000, seed = 7))
        var rolledBack: StoredImageBodyReference? = null
        assertFailsWith<CancellationException> {
            canonicalStore().transaction(scope) {
                rolledBack = (this as TimelineImageBodyWriter).persistImage(other)
                nextRevision()
                throw CancellationException("abort image and event")
            }
        }
        canonicalStore().read(scope) {
            val reader = this as TimelineImageBodyReader
            assertNull(reader.resolveImage(rolledBack!!))
            assertEquals(encoded, reader.resolveImage(first!!))
            assertEquals(0L, checkpoint().revision)
        }
        assertEquals(1, canonicalImageFiles().size)
    }

    @Test
    fun canonicalMissingOrCorruptBodyStaysPlaceholder() = runTest {
        val image = bytes(50_000)
        val source = imageEvent("image", 1.0, image)
        canonicalStore().transaction(scope) {
            TimelineExactCanonicalWriter(scope, 100_000).mergeEvent(this, source)
            nextRevision()
        }
        val body = canonicalImageFiles().single()
        canonicalStore().read(scope) {
            val row = metadata(TimelineReadPosition.Tail, 1).rows.single()
            val stored = TimelineSnapshotCodec.json.decodeFromString(
                StoredTimelineEvent.serializer(), body(row.body, 0, 4096).decodeToString(),
            )
            val reference = stored.attachments.single().bodyReference!!
            val reader = this as TimelineImageBodyReader
            for (invalid in listOf(
                reference.copy(sha256 = "0".repeat(64)),
                reference.copy(decodedBytes = reference.decodedBytes + 1),
                reference.copy(provenance = "unknown"),
                reference.copy(sha256 = "../../escape"),
            )) assertNull(reader.resolveImage(invalid))

            Files.write(body, byteArrayOf(9) + Files.readAllBytes(body).copyOfRange(1, image.size))
            assertNull(reader.resolveImage(reference))
            Files.delete(body)
            assertNull(reader.resolveImage(reference))
            val placeholder = stored.toConfirmedTimelineEventWithImageBodies(reader).attachments.single()
            assertEquals("", placeholder.base64)
            assertEquals(image.size.toLong(), placeholder.storedByteSize)
        }
    }

    // ---- legacy whole-envelope snapshot store ----

    private fun legacyStore(grace: Duration = Duration.ofMinutes(10)) =
        DesktopConfirmedTimelineStore(root.resolve("snapshots"), grace)

    private fun timeline(vararg events: TimelineEvent.Confirmed) =
        Timeline(conversationId = scope.conversationId, events = events.toList().toPersistentList())

    private suspend fun DesktopConfirmedTimelineStore.persist(timeline: Timeline, revision: Long): Boolean =
        writeSnapshot(
            TimelineSnapshotCodec.timelineToStoredEnvelope(timeline, scope, revision, writtenAtMillis = revision)
                .withImageBodies(timeline, this),
        )

    private fun legacyImageFiles(): List<Path> {
        val snapshots = root.resolve("snapshots")
        if (!Files.isDirectory(snapshots)) return emptyList()
        return Files.walk(snapshots).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".bin") }.toList()
        }
    }

    private fun legacyImageDirectories(): List<Path> {
        val snapshots = root.resolve("snapshots")
        if (!Files.isDirectory(snapshots)) return emptyList()
        return Files.walk(snapshots).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString().endsWith(".images") }.toList()
        }
    }

    @Test
    fun legacySnapshotKeepsLargeImageAcrossReopen() = runTest {
        val large = bytes(176_722)
        val small = bytes(2_000, seed = 3)
        assertTrue(legacyStore().persist(timeline(imageEvent("m1", 1.0, large, small)), 1))

        val snapshotJson = Files.walk(root.resolve("snapshots")).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.toList()
        }.single().let(Files::readString)
        assertFalse(snapshotJson.contains(encode(large)))
        assertTrue(snapshotJson.contains(encode(small)), "small images stay inline")

        val reopened = legacyStore()
        val stored = assertNotNull(reopened.readSnapshot(scope))
        val restored = stored.toTimelineWithImageBodies(reopened)
        val attachments = (restored.events.single() as TimelineEvent.Confirmed).attachments
        assertContentEquals(large, Base64.getDecoder().decode(attachments[0].base64))
        assertContentEquals(small, Base64.getDecoder().decode(attachments[1].base64))
        // Without the body capability the old behaviour is a placeholder, which is the bug.
        val legacyRead = TimelineSnapshotCodec.storedEnvelopeToTimeline(stored)
        assertEquals("", (legacyRead.events.single() as TimelineEvent.Confirmed).attachments[0].base64)
    }

    @Test
    fun legacyDedupesBodiesWithinAScope() = runTest {
        val image = bytes(60_000)
        val store = legacyStore()
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, image), imageEvent("m2", 2.0, image)), 1))
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, image), imageEvent("m2", 2.0, image)), 2))
        assertEquals(1, legacyImageFiles().size)
    }

    @Test
    fun unchangedMessagesReuseTheLastPersistedReference() = runTest {
        val store = legacyStore()
        var persisted = 0
        val counting = object : ConfirmedTimelineImageBodies {
            override suspend fun persistImage(scope: TimelineScope, base64: String): StoredImageBodyReference {
                persisted++
                return store.persistImage(scope, base64)
            }
            override suspend fun resolveImage(scope: TimelineScope, reference: StoredImageBodyReference) =
                store.resolveImage(scope, reference)
        }
        val first = timeline(imageEvent("m1", 1.0, bytes(40_000)))
        val written = TimelineSnapshotCodec.timelineToStoredEnvelope(first, scope, 1).withImageBodies(first, counting)
        assertEquals(1, persisted)
        val second = timeline(imageEvent("m1", 1.0, bytes(40_000)), imageEvent("m2", 2.0, bytes(41_000, seed = 2)))
        val next = TimelineSnapshotCodec.timelineToStoredEnvelope(second, scope, 2)
            .withImageBodies(second, counting, previous = written)
        assertEquals(2, persisted, "only the new message's image is hashed and written")
        assertEquals(written.events.single().attachments, next.events.first().attachments)
        assertNotNull(next.events.last().attachments.single().bodyReference)
    }

    @Test
    fun legacyBodiesAreRemovedWithTheirScope() = runTest {
        val store = legacyStore()
        val other = TimelineScope("backend", "other", "agent")
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, bytes(40_000))), 1))
        val otherTimeline = Timeline(other.conversationId, listOf(imageEvent("o1", 1.0, bytes(41_000))).toPersistentList())
        assertTrue(
            store.writeSnapshot(
                TimelineSnapshotCodec.timelineToStoredEnvelope(otherTimeline, other, 1, writtenAtMillis = 5)
                    .withImageBodies(otherTimeline, store),
            ),
        )
        assertEquals(2, legacyImageDirectories().size)

        store.deleteSnapshot(scope)
        assertEquals(1, legacyImageDirectories().size)
        assertEquals(1, legacyImageFiles().size)

        // Prune drops the older conversation and its bodies with it.
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, bytes(40_000))), 2))
        store.prune("backend", 1)
        assertEquals(1, legacyImageDirectories().size)
        assertNull(store.readSnapshot(scope))

        store.clearForBackend("backend")
        assertTrue(legacyImageFiles().isEmpty())
    }

    @Test
    fun legacyReclaimsBodiesNoSnapshotNamesAfterGrace() = runTest {
        val store = legacyStore(grace = Duration.ZERO)
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, bytes(40_000))), 1))
        assertEquals(1, legacyImageFiles().size)
        Thread.sleep(20)
        assertTrue(store.persist(timeline(imageEvent("m2", 2.0, bytes(45_000, seed = 9))), 2))
        assertEquals(1, legacyImageFiles().size)

        // Inside the grace window an unreferenced body is kept for the write about to name it.
        val graceful = legacyStore()
        val staged = graceful.persistImage(scope, encode(bytes(50_000, seed = 11)))
        assertTrue(graceful.persist(timeline(imageEvent("m2", 2.0, bytes(45_000, seed = 9))), 3))
        assertNotNull(graceful.resolveImage(scope, staged))
    }

    @Test
    fun legacyKeepsReferenceWhenAnImageCameBackUnresolved() = runTest {
        val image = bytes(80_000)
        val store = legacyStore()
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, image)), 1))
        // Hydrating with no budget leaves the image a placeholder; writing that back keeps the body.
        val placeholder = assertNotNull(store.readSnapshot(scope)).toTimelineWithImageBodies(store, maxDecodedImageBytes = 0)
        assertEquals("", (placeholder.events.single() as TimelineEvent.Confirmed).attachments.single().base64)
        assertTrue(store.persist(placeholder, 2))
        val restored = assertNotNull(store.readSnapshot(scope)).toTimelineWithImageBodies(store)
        assertContentEquals(
            image,
            Base64.getDecoder().decode((restored.events.single() as TimelineEvent.Confirmed).attachments.single().base64),
        )
    }

    @Test
    fun legacyResolvesNewestFirstWithinBudgetAndMissingBodyStaysPlaceholder() = runTest {
        val older = bytes(40_000)
        val newer = bytes(40_000, seed = 5)
        val store = legacyStore()
        assertTrue(store.persist(timeline(imageEvent("m1", 1.0, older), imageEvent("m2", 2.0, newer)), 1))
        val stored = assertNotNull(store.readSnapshot(scope))

        val budgeted = stored.toTimelineWithImageBodies(store, maxDecodedImageBytes = 50_000)
        val events = budgeted.events.map { it as TimelineEvent.Confirmed }
        assertEquals("", events[0].attachments.single().base64)
        assertEquals(older.size.toLong(), events[0].attachments.single().storedByteSize)
        assertContentEquals(newer, Base64.getDecoder().decode(events[1].attachments.single().base64))

        legacyImageFiles().forEach(Files::delete)
        val missing = stored.toTimelineWithImageBodies(store).events.map { it as TimelineEvent.Confirmed }
        assertTrue(missing.all { it.attachments.single().base64.isEmpty() })
        assertEquals(newer.size.toLong(), missing[1].attachments.single().storedByteSize)
    }
}
