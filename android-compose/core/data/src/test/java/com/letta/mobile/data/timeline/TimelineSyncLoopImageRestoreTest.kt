package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSyncLoopImageRestoreTest {


    private val loops = mutableListOf<TimelineSyncLoop>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        loops.forEach { it.close() }
        loops.clear()
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private class FakeStore : PendingLocalStore {
        val rows = mutableMapOf<String, PendingLocalRecord>()
        override suspend fun save(record: PendingLocalRecord) { rows[record.otid] = record }
        override suspend fun delete(otid: String) { rows.remove(otid) }
        override suspend fun load(conversationId: String): List<PendingLocalRecord> =
            rows.values.filter { it.conversationId == conversationId }.sortedBy { it.sentAt }
    }

    @Test
    fun `send with attachments writes to disk store synchronously`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes.add(it) }
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-img", scope, pendingLocalStore = store).also { loops.add(it) }

        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")
        val otid = sync.send(content = "look at this", attachments = listOf(image))

        // save() is invoked inline within send(); no scheduler advancement needed.
        assertEquals(1, store.rows.size)
        val saved = store.rows[otid]!!
        assertEquals("conv-img", saved.conversationId)
        assertEquals("look at this", saved.content)
        assertEquals(1, saved.attachments.size)
        assertEquals("AAAA", saved.attachments.first().base64)
    }

    @Test
    fun `hydrate restores disk-persisted Local when server has no matching otid`() = runBlocking {
        // Mirrors the observed Letta behavior (mge5.24): the server stored
        // an assistant reply but no user_message for the image send.
        val api = FakeSyncApi()
        api.addStoredMessage(AssistantMessage(id = "asst-1", contentRaw = JsonPrimitive("got it")))
        val store = FakeStore()
        val image = MessageContentPart.Image(base64 = "BBBB", mediaType = "image/png")
        store.rows["otid-orphan"] = PendingLocalRecord(
            otid = "otid-orphan",
            conversationId = "conv-restore",
            content = "from a previous session",
            attachments = listOf(image),
            sentAt = java.time.Instant.parse("2026-04-19T13:00:00Z"),
        )

        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes.add(it) }
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-restore", scope, pendingLocalStore = store).also { loops.add(it) }
        sync.hydrate()

        val restored = sync.state.value.events.filterIsInstance<TimelineEvent.Local>()
            .firstOrNull { it.otid == "otid-orphan" }
        assertNotNull("Expected disk-persisted Local to be restored", restored)
        assertEquals("from a previous session", restored!!.content)
        assertEquals(1, restored.attachments.size)
        assertEquals(DeliveryState.SENT, restored.deliveryState)
    }

    @Test
    fun `hydrate skips disk-persisted Local when server already has the otid`() = runBlocking {
        // If the server eventually does start persisting image messages (or
        // we left a stale row around for a text send), reconcile/hydrate must
        // NOT double-render the bubble.
        val api = FakeSyncApi()
        api.addStoredMessage(UserMessage(id = "msg-1", contentRaw = JsonPrimitive("here"), otid = "otid-dup"))
        val store = FakeStore()
        store.rows["otid-dup"] = PendingLocalRecord(
            otid = "otid-dup",
            conversationId = "conv-dedup",
            content = "here",
            attachments = persistentListOf(),
            sentAt = java.time.Instant.parse("2026-04-19T13:00:00Z"),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes.add(it) }
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-dedup", scope, pendingLocalStore = store).also { loops.add(it) }
        sync.hydrate()

        val withOtid = sync.state.value.events.filter { it.otid == "otid-dup" }
        assertEquals("Expected exactly one event for otid-dup, not a duplicate", 1, withOtid.size)
        assertTrue("Server-confirmed event should win over disk Local", withOtid.first() is TimelineEvent.Confirmed)
    }

    @Test
    fun `text-only send does not write to store`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined).also { scopes.add(it) }
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-text", scope, pendingLocalStore = store).also { loops.add(it) }

        sync.send(content = "just words")

        assertEquals("Text-only sends must not be persisted", 0, store.rows.size)
    }
}
