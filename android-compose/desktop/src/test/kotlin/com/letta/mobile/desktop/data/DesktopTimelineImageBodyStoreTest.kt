package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineEngine
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineEngineOpen
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineExactCanonicalWriter
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineSettledPresentation
import com.letta.mobile.data.timeline.snapshot.StoredImageAttachmentPointer
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyReader
import com.letta.mobile.data.timeline.snapshot.TimelineImageBodyWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEventWithImageBodies
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Images over the 16 KB inline thumbnail budget must survive a restart in Desktop's canonical
 * ledger.
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
        root.toFile().deleteRecursively()
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
}
