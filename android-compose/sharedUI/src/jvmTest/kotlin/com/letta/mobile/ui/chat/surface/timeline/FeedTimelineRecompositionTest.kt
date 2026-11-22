@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.common.GroupPosition
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * The real shared list over a feed-backed presentation: when the feed publishes a growing reply (the reply's row is a new
 * value, every other row the very instance it was), Compose recomposes that one row and the list is never replaced. The
 * ledger is never asked for anything: its transport fails the test on any call.
 */
class FeedTimelineRecompositionTest {
    private val main = ManualMainDispatcher()

    @BeforeTest fun installMain() = Dispatchers.setMain(main)

    @AfterTest fun removeMain() = Dispatchers.resetMain()

    private fun row(id: String, role: String, text: String, minute: Int) = ChatRenderItem.Single(
        UiMessage(id, role, text, timestamp = "2026-10-28T12:%02d:00Z".format(minute)), GroupPosition.None,
    )

    private val noLedger: TimelineTransport = Proxy.newProxyInstance(
        TimelineTransport::class.java.classLoader, arrayOf(TimelineTransport::class.java),
    ) { _, method, _ -> fail("a feed-backed list must not touch the ledger transport: ${method.name}") } as TimelineTransport

    private val older = listOf(
        row("a-3", "assistant", "Third answer", 5), row("u-3", "user", "Third question", 4),
        row("a-2", "assistant", "Second answer", 3), row("u-2", "user", "Second question", 2),
        row("a-1", "assistant", "First answer", 1), row("u-1", "user", "First question", 0),
    )

    private val tokens = listOf("Growing", "Growing reply", "Growing reply, done")

    @Test
    fun aGrowingReplyRecomposesOnlyItsOwnRowAndNeverReplacesTheList() {
        // The oracle: what the growing row costs per token when it is the only row. Six settled neighbours must add nothing.
        val alone = compositionsPerToken(emptyList())
        val withNeighbours = compositionsPerToken(older)
        assertEquals(tokens.size, alone.size)
        assertTrue(alone.all { it > 0 }, "each token redraws the growing row: $alone")
        assertEquals(alone, withNeighbours, "a token never redraws a neighbour: alone=$alone, six neighbours=$withNeighbours")
    }

    /** Row compositions per token while the feed grows its newest row above [settledRows]. */
    private fun compositionsPerToken(settledRows: List<ChatRenderItem>): List<Int> {
        val perToken = mutableListOf<Int>()
        runComposeUiTest {
            val scope = TimelineScope("feed-backend", "feed-conversation", "agent")
            val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coordinator = CanonicalTimelineCoordinator(UiFrameTimelineStore(), noLedger)
            val owner = runBlocking { coordinator.acquire(scope) }
            val feed = MutableStateFlow<List<ChatRenderItem>?>(listOf(row("a-4", "assistant", "Grow", 6)) + settledRows)
            val presentation = runBlocking { CanonicalTimelinePresentation.open(coordinator, owner, ui, feed = { feed }) }
            RowCompositionCounter().use { counter ->
                try {
                    show(presentation)
                    awaitText("Grow")
                    settledRows.lastOrNull()?.let { awaitText(it.messagesInOrder().first().content) }
                    counter.takeDelta()
                    tokens.forEach { text ->
                        feed.value = listOf(row("a-4", "assistant", text, 6)) + settledRows
                        awaitText(text)
                        perToken += counter.takeDelta()
                    }
                    assertEquals(settledRows.size + 1, presentation.live.value.size, "the list is the feed's rows, no more")
                } finally {
                    runBlocking { presentation.close() }
                    ui.cancel()
                }
            }
        }
        return perToken
    }

    private fun ComposeUiTest.show(presentation: CanonicalTimelinePresentation) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 1600.dp)) {
                    ChatTimeline(
                        state = ChatUiState(
                            conversationState = ConversationState.Ready("feed-conversation"), isLoadingMessages = false,
                        ),
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

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            main.drain()
            onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        main.drain()
        waitForIdle()
    }
}
