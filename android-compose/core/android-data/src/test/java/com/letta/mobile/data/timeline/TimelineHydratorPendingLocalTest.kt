package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-by4vm.17: hydration reads pending (unsent) messages for the loop's own
 * (backend, agent, conversation) scope, surfaces a failed read instead of silently
 * dropping the unsent bubbles, never swallows cancellation, and retries a failed
 * reconcile-time delete once the server echo is visible.
 */
class TimelineHydratorPendingLocalTest {

    private class ScriptedStore(private val loadFailure: Throwable? = null) : PendingLocalStore {
        val rows = linkedMapOf<String, PendingLocalRecord>()
        val loadedScopes = mutableListOf<PendingLocalScope>()
        val saved = mutableListOf<PendingLocalRecord>()

        override suspend fun save(record: PendingLocalRecord) {
            saved += record
            rows[record.otid] = record
        }

        override suspend fun delete(otid: String) {
            rows.remove(otid)
        }

        override suspend fun markFailed(otid: String) = Unit

        override suspend fun load(scope: PendingLocalScope): List<PendingLocalRecord> {
            loadedScopes += scope
            loadFailure?.let { throw it }
            return rows.values.toList()
        }
    }

    private fun record(otid: String) = PendingLocalRecord(
        otid = otid,
        conversationId = CONVERSATION,
        content = "unsent $otid",
        attachments = emptyList(),
        sentAt = Instant.parse("2026-10-14T10:00:00Z"),
    )

    private fun scopedLoop(store: PendingLocalStore, api: FakeSyncApi, scope: CoroutineScope) = TimelineSyncLoop(
        messageApi = MessageApiTimelineTransport(api),
        conversationId = CONVERSATION,
        scope = scope,
        pendingLocalStore = store,
        startStreamSubscriber = false,
        agentId = "agent-a",
        timelineScope = TimelineScope(backendId = "backend-a", conversationId = CONVERSATION, agentId = "agent-a"),
    )

    @Test
    fun hydrateLoadsPendingLocalsForTheLoopsOwnBackendAgentAndConversation() = runBlocking {
        val store = ScriptedStore().apply { rows["otid-1"] = record("otid-1") }
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val loop = scopedLoop(store, FakeSyncApi(), scope)

        loop.hydrate()

        assertEquals(listOf(PendingLocalScope("backend-a", CONVERSATION, "agent-a")), store.loadedScopes)
        assertTrue(loop.state.value.events.any { it.otid == "otid-1" && it is TimelineEvent.Local })
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun imageSendPersistsTheLoopsBackendAndAgent() = runBlocking {
        val store = ScriptedStore()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val loop = scopedLoop(store, FakeSyncApi(), scope)

        val otid = loop.send(content = "look", attachments = listOf(MessageContentPart.Image("AAAA", "image/png")))

        val saved = store.saved.first { it.otid == otid }
        assertEquals("backend-a", saved.backendId)
        assertEquals("agent-a", saved.agentId)
        assertEquals(CONVERSATION, saved.conversationId)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun pendingLocalLoadFailureSurfacesUnavailableErrorAndStillHydrates() = runBlocking {
        val store = ScriptedStore(loadFailure = IllegalStateException("disk I/O error"))
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val loop = scopedLoop(store, FakeSyncApi(), scope)
        val events = mutableListOf<TimelineSyncEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { loop.events.collect { events += it } }

        loop.hydrate()

        val unavailable = events.filterIsInstance<TimelineSyncEvent.StreamError>()
            .single { it.type == PENDING_LOCAL_UNAVAILABLE }
        assertEquals("Unsent messages couldn't be restored", unavailable.message)
        assertTrue(events.any { it is TimelineSyncEvent.Hydrated })
        assertTrue(loop.hasHydratedSuccessfully)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun pendingLocalLoadCancellationIsNotSwallowed() = runBlocking {
        val store = ScriptedStore(loadFailure = CancellationException("caller left"))
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val loop = scopedLoop(store, FakeSyncApi(), scope)

        val thrown = try {
            loop.hydrate()
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertEquals("caller left", thrown?.message)
        assertFalse(loop.hasHydratedSuccessfully)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun hydrateDeletesPendingLocalAlreadyEchoedByTheServer() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(UserMessage(id = "msg-1", contentRaw = JsonPrimitive("unsent otid-echoed"), otid = "otid-echoed"))
        val store = ScriptedStore().apply {
            rows["otid-echoed"] = record("otid-echoed")
            rows["otid-unsent"] = record("otid-unsent")
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val loop = scopedLoop(store, api, scope)

        loop.hydrate()

        assertEquals(listOf("otid-unsent"), store.rows.keys.toList())
        assertEquals(1, loop.state.value.events.count { it.otid == "otid-echoed" })
        scope.coroutineContext.job.cancel()
    }

    private companion object {
        const val CONVERSATION = "conv-shared"
    }
}
