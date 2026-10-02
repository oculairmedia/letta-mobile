package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.timeline.TimelineImageRecovery.Companion.imageRecoveryTargets
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineImageBodies
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredImageAttachmentPointer
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-a02be: an image saved size-only before image bodies were persisted comes back from
 * the server, and is then kept as a body so it survives the next restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineImageRecoveryTest {
    private val image = "QUJD".repeat(6_000) // 18 000 decoded bytes, over the inline budget
    private val otherImage = "WFla".repeat(5_000)

    private fun placeholderRow(serverId: String, position: Double, vararg sizes: Long) = StoredTimelineEvent(
        position = position,
        otid = "otid-$serverId",
        content = "look at this",
        serverId = serverId,
        messageType = "USER",
        dateIso = "2026-09-27T22:52:39Z",
        attachments = sizes.map { StoredImageAttachmentPointer("image/jpeg", it) },
    ).toConfirmedTimelineEvent()

    private fun textRow(serverId: String, position: Double) = StoredTimelineEvent(
        position = position,
        otid = "otid-$serverId",
        content = "hello",
        serverId = serverId,
        messageType = "USER",
        dateIso = "2026-09-27T22:50:00Z",
    ).toConfirmedTimelineEvent()

    private fun serverImageMessage(serverId: String, vararg images: String): LettaMessage = UserMessage(
        id = serverId,
        date = "2026-09-27T22:52:39Z",
        contentRaw = buildContentParts(
            "look at this",
            images.map { MessageContentPart.Image(base64 = it, mediaType = "image/jpeg") },
        ).toJsonArray(),
    )

    private fun timelineOf(vararg events: TimelineEvent) =
        Timeline(conversationId = "conv", events = persistentListOf(*events))

    private class PageTransport(
        private val pages: (after: String?, order: String?) -> List<LettaMessage>,
    ) : TimelineTransport by EmptyTimelineTransport {
        val requests = mutableListOf<Triple<String?, String?, Int?>>()
        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            requests += Triple(after, order, limit)
            return pages(after, order)
        }
    }

    // ---- shared merge helpers ----

    @Test
    fun placeholderTakesTheServerImageOfMatchingSize() {
        val placeholder = MessageContentPart.Image("", "image/jpeg", storedByteSize = 18_000)
        val filled = listOf(placeholder).fillSizeOnlyPlaceholders(
            listOf(
                MessageContentPart.Image(otherImage, "image/jpeg"),
                MessageContentPart.Image(image, "image/jpeg"),
            ),
        )
        assertEquals(listOf(MessageContentPart.Image(image, "image/jpeg")), filled)
    }

    @Test
    fun placeholderWithoutCandidateIsKept() {
        val held = listOf(MessageContentPart.Image("", "image/png", storedByteSize = 10))
        assertSame(held, held.fillSizeOnlyPlaceholders(listOf(MessageContentPart.Image(image, "image/jpeg"))))
        assertSame(held, held.fillSizeOnlyPlaceholders(emptyList()))
    }

    @Test
    fun streamMergeReplacesThePlaceholderInsteadOfShowingBoth() {
        val merged = listOf(MessageContentPart.Image("", "image/jpeg", storedByteSize = 18_000))
            .mergeIncomingImages(listOf(MessageContentPart.Image(image, "image/jpeg")))
        assertEquals(listOf(MessageContentPart.Image(image, "image/jpeg")), merged)
    }

    @Test
    fun recentReconcileFillsAKnownRowsPlaceholder() {
        val timeline = timelineOf(textRow("msg-0", 1.0), placeholderRow("msg-1", 2.0, 18_000))
        val (merged, appended) = timeline.mergeServerMessages(listOf(serverImageMessage("msg-1", image)))
        assertEquals(0, appended)
        val row = merged.findByServerId("msg-1")
        assertNotNull(row)
        assertEquals(listOf(image), row.attachments.map { it.base64 })
        assertNull(row.attachments.single().storedByteSize)
        assertEquals(2, merged.events.size)
    }

    // ---- targeted recovery ----

    @Test
    fun recoveryReadsThePageAfterThePreviousRowNewestFirst() = runTest {
        val timeline = timelineOf(
            placeholderRow("msg-0", 1.0, 15_000),
            textRow("msg-1", 2.0),
            placeholderRow("msg-2", 3.0, 18_000),
        )
        assertEquals(
            listOf(
                TimelineImageRecovery.Target("msg-2", "otid-msg-2", "msg-1"),
                TimelineImageRecovery.Target("msg-0", "otid-msg-0", null),
            ),
            timeline.imageRecoveryTargets(),
        )
        val transport = PageTransport { after, _ ->
            when (after) {
                "msg-1" -> listOf(serverImageMessage("msg-2", image))
                null -> listOf(serverImageMessage("msg-0", otherImage), textMessage("msg-1"))
                else -> emptyList()
            }
        }
        val restored = mutableListOf<Pair<String, List<String>>>()
        val recovery = TimelineImageRecovery("conv", transport, { timeline }, { serverId, images ->
            restored += serverId to images.map { it.base64 }
            true
        })

        assertEquals(2, recovery.recover())
        assertEquals(listOf("msg-2" to listOf(image), "msg-0" to listOf(otherImage)), restored)
        assertEquals(listOf(Triple("msg-1", "asc", 8), Triple<String?, String?, Int?>(null, "asc", 8)), transport.requests)

        // Cached: the same rows are never asked for again in this loop's lifetime.
        assertEquals(0, recovery.recover())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun failedOrMissingFetchLeavesThePlaceholder() = runTest {
        val timeline = timelineOf(textRow("msg-0", 1.0), placeholderRow("msg-1", 2.0, 18_000))
        var restores = 0
        val failing = PageTransport { _, _ -> error("offline") }
        assertEquals(0, TimelineImageRecovery("conv", failing, { timeline }, { _, _ -> restores++; true }).recover())
        val missing = PageTransport { _, _ -> listOf(textMessage("msg-7")) }
        assertEquals(0, TimelineImageRecovery("conv", missing, { timeline }, { _, _ -> restores++; true }).recover())
        assertEquals(0, restores)
    }

    @Test
    fun hydratedLoopRecoversThePlaceholderAndPersistsItsBody() = runTest {
        val scope = TimelineScope("backend", "conv", "agent")
        val store = BodyKeepingStore()
        val transport = PageTransport { after, order ->
            if (order == "asc" && after == "msg-0") listOf(serverImageMessage("msg-1", image)) else emptyList()
        }
        val loop = TimelineSyncLoop(
            messageApi = transport,
            conversationId = "conv",
            scope = this,
            startStreamSubscriber = false,
            confirmedTimelineStore = store,
            timelineScope = scope,
            initialTimeline = timelineOf(textRow("msg-0", 1.0), placeholderRow("msg-1", 2.0, 18_000)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        loop.hydrate()
        advanceUntilIdle()

        val row = loop.state.value.findByServerId("msg-1")
        assertNotNull(row)
        assertEquals(listOf(image), row.attachments.map { it.base64 })
        assertEquals(listOf(image), store.bodies.values.toList())
        val pointer = store.readSnapshot(scope)?.events?.single { it.serverId == "msg-1" }?.attachments?.single()
        assertNotNull(pointer?.bodyReference)
        assertTrue(transport.requests.any { it.first == "msg-0" && it.second == "asc" })
        loop.closeAndJoin()
    }

    private fun textMessage(serverId: String): LettaMessage =
        UserMessage(id = serverId, date = "2026-09-27T22:50:00Z", contentRaw = JsonPrimitive("hello"))

    private class BodyKeepingStore(
        private val delegate: ConfirmedTimelineStore = InMemoryConfirmedTimelineStore(),
    ) : ConfirmedTimelineStore by delegate, ConfirmedTimelineImageBodies {
        val bodies = linkedMapOf<StoredImageBodyReference, String>()

        override suspend fun persistImage(scope: TimelineScope, base64: String): StoredImageBodyReference {
            val reference = StoredImageBodyReference(sha256 = "sha-${base64.hashCode()}", decodedBytes = base64.length * 3L / 4L)
            bodies[reference] = base64
            return reference
        }

        override suspend fun resolveImage(scope: TimelineScope, reference: StoredImageBodyReference): String? =
            bodies[reference]
    }
}
