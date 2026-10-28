@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.CanonicalExternalTransportWriter
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.IndexedCanonicalTimelineMaintenance
import com.letta.mobile.data.timeline.TimelineRemotePageAdapter
import com.letta.mobile.data.timeline.TimelineRemotePageRequest
import com.letta.mobile.data.timeline.TimelinePageProgress
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.DockedReplyCard
import com.letta.mobile.ui.chat.surface.DockedReplyParams
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Click the real shared list's actions for both overlay and paged failures. */
class PendingTimelineActionsUiTest {
    private val main = ManualMainDispatcher()

    @BeforeTest fun installMain() { Dispatchers.setMain(main) }
    @AfterTest fun removeMain() { Dispatchers.resetMain() }

    @Test fun liveFailureOffersWorkingRetryAndDismiss() = actions(inHistory = false)
    @Test fun pagedFailureOffersWorkingRetryAndDismiss() = actions(inHistory = true)

    @Test fun dockedLiveFailureOffersWorkingRetryAndDismiss() = actions(inHistory = false, docked = true)
    @Test fun dockedPagedFailureOffersWorkingRetryAndDismiss() = actions(inHistory = true, docked = true)

    private fun actions(inHistory: Boolean, docked: Boolean = false) = runComposeUiTest {
        val scope = TimelineScope("backend", "bubble-conversation", "agent")
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = UiFrameTimelineStore()
        val transport = historyTransport(inHistory)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = runBlocking { coordinator.acquire(scope) }
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, ui, { _, _, _ -> }, { throw it })
        val writer = CanonicalExternalTransportWriter(coordinator, { _, _ -> scope }, maintenance)
        val retried = CopyOnWriteArrayList<Pair<String, String>>()
        val retryStarted = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        writer.bindPendingRetry(AgentId("agent")) { conversation, record ->
            retryStarted.complete(Unit)
            finishRetry.await()
            retried += conversation.value to record.otid
        }
        val record = CanonicalPendingLocalStore.Record("own-otid", "Prompt to dismiss", emptyList(), "2026-10-28T12:00:00Z")
        val presentation = runBlocking {
            coordinator.appendPending(owner, record)
            coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
            coordinator.reconcileRecent(owner)
            CanonicalTimelinePresentation.open(coordinator, owner, ui)
        }
        try {
            showTimeline(presentation, scope, docked)
            assertFailedRow(presentation, inHistory)
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 10_000) { main.drain(); retryStarted.isCompleted }
            waitUntil(timeoutMillis = 10_000) { main.drain(); onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty() }
            assertEquals(CanonicalPendingLocalStore.Delivery.Sending, owner.session.pending.value.single().delivery)
            finishRetry.complete(Unit)
            waitUntil(timeoutMillis = 10_000) { main.drain(); retried.isNotEmpty() }
            assertEquals(listOf(scope.conversationId to record.otid), retried.toList())
            runBlocking { coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Failed) }
            awaitRetry()
            onNodeWithText("Dismiss").performClick()
            waitUntil(timeoutMillis = 10_000) { main.drain(); owner.session.pending.value.isEmpty() }
            waitUntil(timeoutMillis = 10_000) { main.drain(); onAllNodesWithText("Dismiss").fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { CanonicalPendingLocalStore(store).load(scope) }.isEmpty())
        } finally {
            runBlocking { presentation.close() }
            ui.cancel()
        }
    }

    private fun historyTransport(inHistory: Boolean): TimelineTransport {
        val history = if (inHistory) listOf(AssistantMessage(
            "newer", contentRaw = JsonPrimitive("Newer history"), date = "2026-10-28T12:01:00Z",
        )) else emptyList()
        val unexpected = Proxy.newProxyInstance(TimelineTransport::class.java.classLoader, arrayOf(TimelineTransport::class.java)) {
            _, method, _ -> fail("Unexpected transport call: ${method.name}")
        } as TimelineTransport
        return object : TimelineTransport by unexpected {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageAdapter.fromMessages(request, history, hasMore = false)
        }
    }

    private fun ComposeUiTest.showTimeline(
        presentation: CanonicalTimelinePresentation,
        scope: TimelineScope,
        docked: Boolean,
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 720.dp)) {
                    val state = ChatUiState(conversationState = ConversationState.Ready(scope.conversationId), isLoadingMessages = false)
                    if (docked) {
                        DockedReplyCard(DockedReplyParams(
                            state, presentation, RecordingChatActions(), ChatSurfaceCapabilities.Default,
                            ChatSurfaceHost(), ChatSurfaceAppearance(), {},
                        ))
                    } else ChatTimeline(
                        state = state,
                        pagedTimeline = presentation,
                        actions = RecordingChatActions(),
                        capabilities = ChatSurfaceCapabilities.Default,
                        host = ChatSurfaceHost(),
                        appearance = ChatSurfaceAppearance(),
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.awaitRetry() = waitUntil(timeoutMillis = 10_000) {
        main.drain()
        onAllNodesWithText("Retry").fetchSemanticsNodes().size == 1
    }

    private fun ComposeUiTest.assertFailedRow(presentation: CanonicalTimelinePresentation, inHistory: Boolean) {
        awaitRetry()
        // The live bubble can draw before Paging has reported resident history. Wait for
        // the intended path so this test cannot pass by clicking its transient overlay twin.
        waitUntil(timeoutMillis = 10_000) {
            main.drain()
            presentation.live.value.any { it.key == "msg-own-otid" } == !inHistory
        }
        awaitRetry()
        onNodeWithText("Dismiss").assertExists()
        assertEquals(!inHistory, presentation.live.value.any { it.key == "msg-own-otid" }, "exercise the intended row path")
    }
}
