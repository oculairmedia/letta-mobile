package com.letta.mobile.feature.chat.screen.shared

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
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
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentBarState
import com.letta.mobile.feature.chat.screen.openedChatViewModel
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.chat.subagents.BackgroundTasksTags
import com.letta.mobile.ui.common.GroupPosition
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Emmanuel's 2026-11-26 report: the floating Background-tasks orb overlaps the right edge of the
 * user prompt bubble resting at the top of the timeline (it cut the prompt's words). The orb hangs
 * from the page's top chrome inset, and the timeline's first row rests at that same inset plus the
 * list's edge gap, so an end-aligned prompt bubble resting at the top of the list and the orb
 * share the same band. While the floating launcher shows, the page must rest the timeline's
 * content below its band, so the orb sits in its own clear pocket above the rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
@Tag("integration")
class SharedChatOrbPlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFloatingLauncherNeverOverlapsTheTopPromptBubble() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rows = MutableStateFlow<List<ChatRenderItem>?>(emptyList())
        val coordinator = CanonicalTimelineCoordinator(EmptyStore(), mockk<TimelineTransport>())
        val canonical = runBlocking {
            val owner = coordinator.acquire(TimelineScope("backend", "conversation-orb", "agent-orb"))
            CanonicalTimelinePresentation.open(coordinator, owner, scope, feed = { rows })
        }
        val navigation = ChatScreenNavigationCallbacks(onOpenBackgroundTasks = {})
        val viewModel = openedChatViewModel(
            ChatPagingHost().apply { openCanonical = { _, _, _, _ -> paging(canonical) } },
            TestData.agent("agent-orb", "Orb"), "conversation-orb", "orb",
        )
        try {
            // The oldest prompt docks at the top as the long reply history fills the viewport.
            rows.value = (1..30).map { index ->
                ChatRenderItem.Single(UiMessage("a$index", "assistant", "Assistant answer $index.", timestamp = STAMP), GroupPosition.None)
            } + ChatRenderItem.Single(UiMessage("u1", "user", PROMPT, timestamp = STAMP), GroupPosition.None)
            compose.setContent {
                OrbOverlapPage(
                    viewModel = viewModel,
                    canonical = canonical,
                    navigation = navigation,
                )
            }
            compose.waitForIdle()
            val orb = compose.onNode(hasTestTag(BackgroundTasksTags.TOGGLE)).getUnclippedBoundsInRoot()
            val bubble = compose.onNode(hasText(PROMPT), useUnmergedTree = true).getUnclippedBoundsInRoot()
            val viewport = compose.onNode(hasTestTag("chat-timeline-list")).getUnclippedBoundsInRoot()
            assertTrue("prompt must be near viewport top: prompt=$bubble viewport=$viewport", bubble.top - viewport.top in 0.dp..96.dp)
            assertTrue("prompt must be right aligned: prompt=$bubble viewport=$viewport", viewport.right - bubble.right <= 48.dp)
            assertFalse(
                "the floating Background-tasks orb $orb overlaps the prompt bubble's text $bubble",
                orb.overlaps(bubble),
            )
            assertTrue(
                "the prompt text must rest below the launcher: orb=$orb, text=$bubble, clearance=${bubble.top - orb.bottom}",
                bubble.top >= orb.bottom,
            )
        } finally {
            viewModel.viewModelScope.cancel()
            runBlocking { canonical.close() }
            scope.cancel()
        }
    }

    private fun paging(canonical: CanonicalTimelinePresentation): ChatPagingPresentation =
        ChatPagingPresentation(flowOf(PagingData.empty()), canonical.live, {}, canonical = canonical)

    /** The phone page with the fixture's canonical rows and this test's launcher inputs. */
    @Composable
    private fun OrbOverlapPage(
        viewModel: AdminChatViewModel,
        canonical: CanonicalTimelinePresentation,
        navigation: ChatScreenNavigationCallbacks,
    ) {
        SharedChatPage(
            SharedChatPageParams(
                session = rememberAdminChatPageSession(viewModel, navigation),
                navigation = navigation,
                chatMode = "interactive",
                fontScale = 1f,
                hapticsEnabled = false,
                pagedTimeline = canonical,
                openOnCanvas = false,
                subagents = SharedChatSubagentInputs(
                    source = viewModel.activeSubagentSource,
                    selfTodoSource = viewModel.selfTodoSource,
                    barState = ChatScreenSubagentBarState(persistentListOf(), 0L, runningCount = 3),
                ),
            ),
        )
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private companion object {
        const val PROMPT = "yes unify android onto the shared page"
        const val STAMP = "2026-01-01T00:00:00Z"
    }

    /** The coordinator's store, holding nothing: the rows under test are the presentation's live ones. */
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
