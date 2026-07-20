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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class TimelineSyncLoopSendTest {
    @Test
    fun `send optimistically appends local event`() = runTest {
        // Uses StandardTestDispatcher (not Unconfined) so the gateway worker
        // and processSendQueue only resume when we explicitly advance the
        // scheduler. That lets us observe the optimistic Local state after the
        // gateway append but before stream+reconcile can swap it to Confirmed.
        val api = FakeSyncApi()
        api.sendResponseGate = CompletableDeferred()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val otidDeferred = async { sync.send("hello") }

        assertEquals(
            "send should submit the Local append to the gateway instead of mutating inline",
            0,
            sync.state.value.events.size,
        )

        testScheduler.runCurrent()

        val otid = otidDeferred.await()
        val local = sync.state.value.findByOtid(otid)

        assertNotNull(local)
        assertTrue(
            "Expected optimistic Local event but found ${local!!::class.simpleName}",
            local is TimelineEvent.Local,
        )
        assertEquals("hello", local.content)
        scope.coroutineContext.job.cancel()
    }
    @Test
    fun `send then stream appends confirmed assistant event`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        sync.state.test {
            assertEquals(0, awaitItem().events.size)

            val userOtidDeferred = async { sync.send("hello") }
            testScheduler.advanceUntilIdle()
            val userOtid = userOtidDeferred.await()

            var timeline = awaitItem()
            while (timeline.findByOtid("reply-otid") == null) {
                timeline = awaitItem()
            }

            val events = timeline.events
            assertEquals(2, events.size)
            assertTrue(events[0].otid == userOtid || events[0].otid.contains(userOtid))
            assertEquals("OK", events[1].content)
            cancelAndIgnoreRemainingEvents()
        }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send with image attachments posts Letta content parts`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-image", contentRaw = JsonPrimitive("I see it"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-image", scope)

        val send = async {
            sync.send(
                content = "describe this image",
                attachments = listOf(MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")),
            )
        }
        advanceUntilIdle()
        send.await()

        val firstMessage = api.lastSendRequest!!.messages!!.single().jsonObject
        val contentParts = firstMessage["content"]!!.jsonArray
        assertEquals("text", contentParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("describe this image", contentParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", contentParts[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = contentParts[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", source["data"]!!.jsonPrimitive.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send without attachments keeps legacy string content`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-text", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-text", scope)

        val send = async { sync.send("plain text") }
        advanceUntilIdle()
        send.await()

        val firstMessage = api.lastSendRequest!!.messages!!.single().jsonObject
        assertEquals(JsonPrimitive("plain text"), firstMessage["content"])
        scope.coroutineContext.job.cancel()
    }

    /**
     * lettabot-uww.11 regression: streaming assistant text deltas that
     * share a serverId must concatenate byte-for-byte even when the
     * delta head matches a prefix of the running accumulator. The
     * pre-fix wucn-snapshot-recovery cascade silently dropped such
     * deltas (`oldText.startsWith(newText) -> oldText`) and produced
     * the 2026-04-26 mermaid field repro `A[LLM snapshots]` →
     * `A[LLMapshots|`.
     *
     * This test drives the timeline-path SSE merge — the dominant
     * render path post-conversation-creation — with a delta sequence
     * engineered to hit every defective branch of the old cascade:
     *   - delta head equals accumulator (silent-drop branch)
     *   - delta is >=32 chars AND >= half the accumulator
     *     (destructive-replace branch)
     */
}
