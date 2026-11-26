package com.letta.mobile.feature.chat.screen.shared

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.GroupPosition
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins where the Android page's timeline rows come from: for each scripted frame, the rows drawn (and their order on
 * screen) are exactly the canonical presentation's live rows, while the port's own message list stays empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
@Tag("integration")
class SharedChatPageAndroidParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderedRowsFollowCanonicalPresentationRatherThanPortMessages() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = MutableStateFlow<List<ChatRenderItem>?>(emptyList())
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), mockk<TimelineTransport>())
        val canonical = runBlocking {
            val owner = coordinator.acquire(TimelineScope("backend", "conversation-parity", "agent-parity"))
            CanonicalTimelinePresentation.open(coordinator, owner, scope, feed = { rows })
        }
        val paging = ChatPagingPresentation(flowOf(PagingData.empty()), canonical.live, {}, canonical = canonical)
        val viewModel = openedChatViewModel(
            ChatPagingHost().apply { openCanonical = { _, _, _, _ -> paging } },
            TestData.agent("agent-parity", "Parity"), "conversation-parity", "parity",
        )
        try {
            compose.setContent {
                CanonicalParityPage(viewModel, paging)
            }
            val frames = listOf(listOf("canonical first"), listOf("canonical second", "canonical first"), listOf("canonical settled"))
            frames.forEach { frame ->
                compose.runOnIdle {
                    rows.value = frame.map { text ->
                        ChatRenderItem.Single(UiMessage(text, "assistant", text, timestamp = "2026-01-01T00:00:00Z"), GroupPosition.None)
                    }
                }
                compose.waitForIdle()
                val expected = canonical.live.value.map { (it as ChatRenderItem.Single).message.content }
                assertEquals(frame, expected)
                expected.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(1) }
                (frames.flatten().toSet() - frame.toSet()).forEach {
                    compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
                }
                val rendered = frame.map {
                    compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().single()
                }.sortedBy { it.boundsInRoot.top }.map { node ->
                    frame.single { text -> node.config.toString().contains(text) }
                }
                assertEquals(frame.reversed(), rendered)
                assertEquals(emptyList<UiMessage>(), viewModel.uiState.value.messages)
            }
        } finally {
            viewModel.viewModelScope.cancel()
            runBlocking { canonical.close() }
            scope.cancel()
        }
    }

    private class EmptyStore : TimelineBoundedStore {
        private val transaction = mockk<TimelineStoreTransaction>(relaxed = true).also {
            coEvery { it.checkpoint() } returns TimelineDurableCheckpoint(0, null, false)
            coEvery { it.metadata(any(), any()) } returns TimelineMetadataPage(emptyList(), null, null, 0)
            coEvery { it.locate(any()) } returns null
            coEvery { it.evidence(any(), any()) } returns null
        }
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = block(transaction)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T = block(transaction)
    }
}
