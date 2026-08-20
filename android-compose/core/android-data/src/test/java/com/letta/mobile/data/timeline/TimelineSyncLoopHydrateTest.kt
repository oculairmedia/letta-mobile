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
class TimelineSyncLoopHydrateTest {
    @Test
    fun `hydrate loads initial messages`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(SystemMessage(id = "m1", contentRaw = JsonPrimitive("welcome")))
        api.addStoredMessage(AssistantMessage(id = "m2", contentRaw = JsonPrimitive("hi")))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        sync.hydrate()

        val timeline = sync.state.value
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events.all { it is TimelineEvent.Confirmed })
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate skips client-side default shim placeholder conversations`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(SystemMessage(id = "m1", contentRaw = JsonPrimitive("welcome")))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-default-agent-1", scope)

        sync.hydrate()

        assertEquals(0, api.listMessagesCalls)
        assertEquals(0, sync.state.value.events.size)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate overfetches raw events so older visible turns survive tool-heavy latest run`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(
            UserMessage(
                id = "older-user",
                contentRaw = JsonPrimitive("older prompt"),
                date = "2026-05-07T09:00:00Z",
            )
        )
        api.addStoredMessage(
            AssistantMessage(
                id = "older-assistant",
                contentRaw = JsonPrimitive("older answer"),
                date = "2026-05-07T09:00:01Z",
            )
        )
        repeat(60) { index ->
            api.addStoredMessage(
                ToolCallMessage(
                    id = "tool-$index",
                    date = "2026-05-07T10:00:${index.toString().padStart(2, '0')}Z",
                    runId = "latest-run",
                )
            )
        }
        api.addStoredMessage(
            UserMessage(
                id = "latest-user",
                contentRaw = JsonPrimitive("latest prompt"),
                date = "2026-05-07T10:01:00Z",
            )
        )
        api.addStoredMessage(
            AssistantMessage(
                id = "latest-assistant",
                contentRaw = JsonPrimitive("latest answer"),
                date = "2026-05-07T10:01:01Z",
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        sync.hydrate(limit = 50)

        val ids = sync.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .map { it.serverId }
        assertTrue("hydrate should request more than the visible target", api.lastConversationLimit!! > 50)
        assertTrue("older visible user message should survive raw overfetch", "older-user" in ids)
        assertTrue("older visible assistant message should survive raw overfetch", "older-assistant" in ids)
        assertTrue("latest turn should still be present", "latest-user" in ids && "latest-assistant" in ids)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate orders reasoning before final assistant response`() = runBlocking {
        val api = FakeSyncApi()
        val runId = "run-order"
        api.addStoredMessage(
            UserMessage(
                id = "user-1",
                contentRaw = JsonPrimitive("prompt"),
                date = "2026-05-07T10:00:00Z",
            )
        )
        // Simulate REST history returning/persisting the final assistant before its reasoning
        // sibling. Hydrate must still render prompt → thinking → answer.
        api.addStoredMessage(
            AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("final answer"),
                date = "2026-05-07T10:00:02Z",
                runId = runId,
            )
        )
        api.addStoredMessage(
            ReasoningMessage(
                id = "reasoning-1",
                reasoning = "thinking",
                date = "2026-05-07T10:00:01Z",
                runId = runId,
            )
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        sync.hydrate()

        val events = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("user-1", "reasoning-1", "assistant-1"), events.map { it.serverId })
        assertEquals(TimelineMessageType.REASONING, events[1].messageType)
        assertEquals(TimelineMessageType.ASSISTANT, events[2].messageType)
        scope.coroutineContext.job.cancel()
    }

}
