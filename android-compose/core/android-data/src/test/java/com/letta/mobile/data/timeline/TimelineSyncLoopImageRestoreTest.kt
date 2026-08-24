package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Integration-level tests for [TimelineSyncLoop] using a programmable fake API.
 *
 * The fake produces real SSE byte channels (so the existing [com.letta.mobile.data.stream.SseParser]
 * exercise path is covered) and an in-memory message store to simulate
 * reconciliation via listMessages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
/**
 * mge5.24: image attachments must survive a process restart even when the
 * server (correctly observed in production) drops user_message records that
 * carry non-text content.
 */
class TimelineSyncLoopImageRestoreTest {

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
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-img", scope, pendingLocalStore = store)

        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")
        val otid = sync.send(content = "look at this", attachments = listOf(image))

        // save() is invoked inline within send(); no scheduler advancement needed.
        assertEquals(1, store.rows.size)
        val saved = store.rows[otid]!!
        assertEquals("conv-img", saved.conversationId)
        assertEquals("look at this", saved.content)
        assertEquals(1, saved.attachments.size)
        assertEquals("AAAA", saved.attachments.first().base64)
        scope.coroutineContext.job.cancel()
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

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-restore", scope, pendingLocalStore = store)
        sync.hydrate()

        val restored = sync.state.value.events.filterIsInstance<TimelineEvent.Local>()
            .firstOrNull { it.otid == "otid-orphan" }
        assertNotNull("Expected disk-persisted Local to be restored", restored)
        assertEquals("from a previous session", restored!!.content)
        assertEquals(1, restored.attachments.size)
        assertEquals(DeliveryState.SENT, restored.deliveryState)
        scope.coroutineContext.job.cancel()
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
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-dedup", scope, pendingLocalStore = store)
        sync.hydrate()

        val withOtid = sync.state.value.events.filter { it.otid == "otid-dup" }
        assertEquals("Expected exactly one event for otid-dup, not a duplicate", 1, withOtid.size)
        assertTrue("Server-confirmed event should win over disk Local", withOtid.first() is TimelineEvent.Confirmed)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `text-only send does not write to store`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-text", scope, pendingLocalStore = store)

        sync.send(content = "just words")
        scope.coroutineContext.job.cancel()

        assertEquals("Text-only sends must not be persisted", 0, store.rows.size)
    }
}
