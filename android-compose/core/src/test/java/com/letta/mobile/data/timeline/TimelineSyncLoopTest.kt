package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level tests for [TimelineSyncLoop] using a programmable fake API.
 *
 * The fake produces real SSE byte channels (so the existing [com.letta.mobile.data.stream.SseParser]
 * exercise path is covered) and an in-memory message store to simulate
 * reconciliation via listMessages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineSyncLoopTest {

    @Test
    fun `hydrate loads initial messages`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(SystemMessage(id = "m1", contentRaw = JsonPrimitive("welcome")))
        api.addStoredMessage(AssistantMessage(id = "m2", contentRaw = JsonPrimitive("hi")))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.hydrate()

        val timeline = sync.state.value
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events.all { it is TimelineEvent.Confirmed })
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send optimistically appends local event`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val otid = sync.send("hello")

        val local = sync.state.value.findByOtid(otid)
        assertNotNull(local)
        assertTrue(local is TimelineEvent.Local)
        assertEquals("hello", local!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send then stream appends confirmed assistant event`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val userOtid = sync.send("hello")

        // Wait until the reply lands
        withTimeout(5_000) {
            while (sync.state.value.findByOtid("reply-otid") == null) delay(10)
        }

        val events = sync.state.value.events
        assertEquals(2, events.size)
        assertTrue(events[0].otid == userOtid || events[0].otid.contains(userOtid))
        assertEquals("OK", events[1].content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile swaps local user event to confirmed`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val userOtid = sync.send("hello")

        // When stream completes, the fake copies sent message + nextStreamMessages into store
        // with our client otid preserved — reconcile should then find it and swap
        withTimeout(5_000) {
            while (sync.state.value.findByOtid(userOtid) !is TimelineEvent.Confirmed) delay(20)
        }

        val swapped = sync.state.value.findByOtid(userOtid)
        assertTrue(swapped is TimelineEvent.Confirmed)
        assertEquals("hello", swapped!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `identical content sent twice produces two local events`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "r1")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val otid1 = sync.send("same")
        val otid2 = sync.send("same")

        assertTrue(otid1 != otid2)
        val e1 = sync.state.value.findByOtid(otid1)
        val e2 = sync.state.value.findByOtid(otid2)
        assertNotNull(e1)
        assertNotNull(e2)
        assertEquals("same", e1!!.content)
        assertEquals("same", e2!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `concurrent sends do not collide on position or otid`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()   // no stream body to simplify
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        // Fire 10 concurrent sends
        val jobs = (1..10).map { i ->
            scope.launch { sync.send("msg $i") }
        }
        jobs.forEach { it.join() }

        val timeline = sync.state.value
        assertEquals(10, timeline.events.size)
        // Positions must be strictly increasing (invariant enforced by Timeline.init)
        val positions = timeline.events.map { it.position }
        assertEquals(positions.sorted(), positions)
        // otids must be unique
        assertEquals(10, timeline.events.map { it.otid }.toSet().size)
        scope.coroutineContext.job.cancel()
    }
}

/**
 * Fake [MessageApi] that simulates:
 * - a stored message list (returned by listMessages)
 * - a programmable stream (returned by sendConversationMessage as a real SSE byte channel)
 *
 * On each send: the user message is added to the store with its otid preserved,
 * and the stream yields [nextStreamMessages] as SSE events.
 */
private class FakeSyncApi : MessageApi(mockk(relaxed = true)) {
    private val stored = mutableListOf<LettaMessage>()
    var nextStreamMessages: List<LettaMessage> = emptyList()

    fun addStoredMessage(msg: LettaMessage) {
        stored.add(msg)
    }

    override suspend fun listMessages(
        agentId: String,
        limit: Int?,
        before: String?,
        after: String?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        return if (order == "desc") stored.reversed() else stored.toList()
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): ByteReadChannel {
        // Extract otid from request and create a UserMessage in the store to
        // mimic server persistence.
        val firstMessage = request.messages?.firstOrNull()
        val otid = firstMessage?.let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("otid")?.let { v ->
                (v as? JsonPrimitive)?.contentOrNull
            }
        }
        val userContent = firstMessage?.let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("content")?.let { v ->
                (v as? JsonPrimitive)?.contentOrNull
            }
        }
        if (otid != null) {
            stored.add(
                UserMessage(
                    id = "message-$otid",
                    contentRaw = JsonPrimitive(userContent ?: ""),
                    otid = otid,
                )
            )
        }

        // Emit nextStreamMessages as real SSE frames and close
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val sseBody = buildString {
            nextStreamMessages.forEach { msg ->
                append("data: ")
                append(json.encodeToString(LettaMessage.serializer(), msg))
                append("\n\n")
            }
            append("data: [DONE]\n\n")
        }

        // Also add stream messages to the store so subsequent listMessages reflects them
        stored.addAll(nextStreamMessages)

        return io.ktor.utils.io.ByteChannel(autoFlush = true).also { channel ->
            kotlinx.coroutines.GlobalScope.launch {
                channel.writeStringUtf8(sseBody)
                channel.close()
            }
        }
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }
