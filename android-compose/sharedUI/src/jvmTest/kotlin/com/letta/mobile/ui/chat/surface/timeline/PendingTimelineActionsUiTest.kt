@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsEnabled
import com.letta.mobile.data.timeline.toRenderItem
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
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import kotlinx.coroutines.CancellationException
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

    @Test fun rejectedRetryShowsFeedbackAndRemainsEnabled() = rejectedAction(retry = true)
    @Test fun rejectedDismissShowsFeedbackAndRemainsEnabled() = rejectedAction(retry = false)

    @Test fun throwingRetryShowsFeedbackAndRemainsEnabled() = throwingAction(retry = true)
    @Test fun throwingDismissShowsFeedbackAndRemainsEnabled() = throwingAction(retry = false)
    @Test fun cancelledRetryRemainsEnabledWithoutRejection() = throwingAction(retry = true, cancelled = true)
    @Test fun cancelledDismissRemainsEnabledWithoutRejection() = throwingAction(retry = false, cancelled = true)

    private fun throwingAction(retry: Boolean, cancelled: Boolean = false) = runComposeUiTest {
        val scope = TimelineScope("throwing-action", "conversation", "agent")
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reject = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
        val delegate = UiFrameTimelineStore()
        val store = object : TimelineBoundedStore by delegate {
            override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
                if (reject.get()) {
                    attempted.set(true)
                    if (cancelled) throw CancellationException("action cancelled")
                    throw AssertionError("pending storage unavailable")
                }
                return delegate.transaction(scope, block)
            }
        }
        val coordinator = CanonicalTimelineCoordinator(store, historyTransport(false))
        val owner = runBlocking { coordinator.acquire(scope) }
        // Retry must get as far as the transaction, rather than returning false for no sender.
        CanonicalExternalTransportWriter(coordinator, { _, _ -> scope },
            IndexedCanonicalTimelineMaintenance(coordinator, ui, { _, _, _ -> }, { throw it }))
            .bindPendingRetry(AgentId("agent")) { _, _ -> }
        val record = CanonicalPendingLocalStore.Record("throwing", "prompt", emptyList(), "2026-10-28T12:00:00Z")
        val presentation = runBlocking {
            coordinator.appendPending(owner, record)
            coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
            CanonicalTimelinePresentation.open(coordinator, owner, ui)
        }
        try {
            val row = record.copy(delivery = CanonicalPendingLocalStore.Delivery.Failed).toRenderItem()
            setContent { MaterialTheme { PendingPromptActions(row, presentation) } }
            reject.set(true)
            val button = if (retry) "Retry" else "Dismiss"
            val feedback = if (retry) "Couldn’t retry this message. Try again." else "Couldn’t dismiss this message. Try again."
            onNodeWithText(button).performClick()
            waitUntil(timeoutMillis = 10_000) { main.drain(); attempted.get() }
            if (cancelled) {
                waitForIdle()
                onNodeWithText(feedback).assertDoesNotExist()
            } else {
                waitUntil(timeoutMillis = 10_000) { main.drain(); onAllNodesWithText(feedback).fetchSemanticsNodes().isNotEmpty() }
            }
            onNodeWithText(button).assertIsEnabled()
            assertEquals(listOf(record.otid), owner.session.pending.value.map { it.otid })
        } finally {
            reject.set(false)
            runBlocking { presentation.close() }
            ui.cancel()
        }
    }

    @Test fun rejectedRetryAfterSuspensionKeepsVisibleFeedback() = runComposeUiTest {
        val scope = TimelineScope("suspended-rejection", "conversation", "agent")
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CanonicalTimelineCoordinator(UiFrameTimelineStore(), historyTransport(false))
        val owner = runBlocking { coordinator.acquire(scope) }
        val writer = CanonicalExternalTransportWriter(coordinator, { _, _ -> scope },
            IndexedCanonicalTimelineMaintenance(coordinator, ui, { _, _, _ -> }, { throw it }))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        writer.bindPendingRetry(AgentId("agent")) { _, _ ->
            started.complete(Unit)
            finish.await()
            error("sender unavailable")
        }
        val record = CanonicalPendingLocalStore.Record("rejected", "prompt", emptyList(), "2026-10-28T12:00:00Z")
        val presentation = runBlocking {
            coordinator.appendPending(owner, record)
            coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
            CanonicalTimelinePresentation.open(coordinator, owner, ui)
        }
        try {
            showTimeline(presentation, scope, docked = false)
            awaitRetry()
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 10_000) { main.drain(); started.isCompleted }
            waitUntil(timeoutMillis = 10_000) { main.drain(); onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty() }
            finish.complete(Unit)
            waitUntil(timeoutMillis = 10_000) {
                main.drain()
                onAllNodesWithText("Couldn’t retry this message. Try again.").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Retry").assertIsEnabled()
        } finally {
            runBlocking { presentation.close() }
            ui.cancel()
        }
    }

    private fun rejectedAction(retry: Boolean) = runComposeUiTest {
        val scope = TimelineScope("rejection-backend", "conversation", "agent")
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CanonicalTimelineCoordinator(UiFrameTimelineStore(), historyTransport(false))
        val owner = runBlocking { coordinator.acquire(scope) }
        val record = CanonicalPendingLocalStore.Record("rejected", "prompt", emptyList(), "2026-10-28T12:00:00Z")
        val presentation = runBlocking {
            coordinator.appendPending(owner, record)
            coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Failed)
            CanonicalTimelinePresentation.open(coordinator, owner, ui)
        }
        val row = record.copy(delivery = CanonicalPendingLocalStore.Delivery.Failed).toRenderItem()
        try {
            setContent { MaterialTheme { PendingPromptActions(row, presentation) } }
            if (!retry) runBlocking { coordinator.markPending(owner, record.otid, CanonicalPendingLocalStore.Delivery.Sending) }
            val button = if (retry) "Retry" else "Dismiss"
            onNodeWithText(button).performClick()
            val message = if (retry) "Couldn’t retry this message. Try again." else "Couldn’t dismiss this message. Try again."
            waitUntil(timeoutMillis = 10_000) { main.drain(); onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(button).assertIsEnabled()
            assertEquals(1, owner.session.pending.value.size)
        } finally {
            runBlocking { presentation.close() }
            ui.cancel()
        }
    }

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
