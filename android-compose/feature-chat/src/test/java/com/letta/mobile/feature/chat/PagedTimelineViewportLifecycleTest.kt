package com.letta.mobile.feature.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.LocalTimelineRowLifecycleObserver
import com.letta.mobile.feature.chat.screen.PagedChatMessageList
import com.letta.mobile.feature.chat.screen.PagedTimelineLazyLayout
import com.letta.mobile.feature.chat.screen.PagedTimelineViewportParams
import com.letta.mobile.feature.chat.screen.TimelineRowLifecycle
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PagedTimelineViewportLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String) = ChatRenderItem.Single(
        UiMessage(id = id, role = "user", content = id, timestamp = "2026-09-07T00:00:00Z"),
        GroupPosition.None,
    )

    @Test fun realPagerReceivesEdgeAccessHintsAndLoadsBeyondInitialWindow() {
        val loads = mutableListOf<PagingSource.LoadParams<Int>>()
        val pager = Pager(PagingConfig(pageSize = 10, prefetchDistance = 1, enablePlaceholders = true, maxSize = 30)) {
            object : PagingSource<Int, ChatRenderItem>() {
                override fun getRefreshKey(state: PagingState<Int, ChatRenderItem>): Int? = state.anchorPosition
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChatRenderItem> {
                    synchronized(loads) { loads += params }
                    val start = params.key ?: 0
                    val end = (start + params.loadSize).coerceAtMost(60)
                    return LoadResult.Page(
                        data = (start until end).map { row("paged-$it") },
                        prevKey = (start - params.loadSize).takeIf { it >= 0 },
                        nextKey = end.takeIf { it < 60 },
                        itemsBefore = start,
                        itemsAfter = 60 - end,
                    )
                }
            }
        }
        val presentation = ChatPagingPresentation(pager.flow, MutableStateFlow(emptyList()), {})
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(),
                )
            }
        }

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(28)
        compose.waitUntil(10_000) { synchronized(loads) { loads.any { it is PagingSource.LoadParams.Append } } }
        compose.runOnIdle {
            assertTrue(
                "edge access must issue an append hint",
                synchronized(loads) { loads.any { it is PagingSource.LoadParams.Append } },
            )
        }
    }

    @Test fun fiveLiveToSettledHandoffsKeepActualComposeSlotsMounted() {
        val unrelated = row("unrelated")
        val live = MutableStateFlow<List<ChatRenderItem>>(emptyList())
        val settled = MutableStateFlow<PagingData<ChatRenderItem>>(PagingData.from(listOf(unrelated)))
        val presentation = ChatPagingPresentation(settled, live, {})
        val mounts = mutableMapOf<String, Int>()
        val disposes = mutableMapOf<String, Int>()
        compose.setContent {
            LettaChatTheme {
                CompositionLocalProvider(LocalTimelineRowLifecycleObserver provides { event, key ->
                    val target = if (event == TimelineRowLifecycle.Mount) mounts else disposes
                    target[key] = target.getOrDefault(key, 0) + 1
                }) {
                    PagedChatMessageList(
                        presentation, ChatUiState(),
                        ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        ), ChatContentAppearance(),
                    )
                }
            }
        }

        repeat(5) { cycle ->
            val key = "segment-stream-$cycle"
            val liveRow = row("live-$cycle").copy(keyOverride = key)
            val settledRow = row("canonical-$cycle").copy(
                message = row("canonical-$cycle").message.copy(content = "settled-$cycle"),
                keyOverride = key,
            )
            compose.runOnIdle { live.value = listOf(liveRow) }
            compose.onNodeWithText("live-$cycle").assertIsDisplayed()
            compose.runOnIdle { settled.value = PagingData.from(listOf(settledRow, unrelated)) }
            compose.onNodeWithText("settled-$cycle").assertIsDisplayed()
            compose.onNodeWithText("live-$cycle").assertDoesNotExist()
            compose.runOnIdle { live.value = emptyList() }
            compose.onNodeWithText("settled-$cycle").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("cycle $cycle mounted once", 1, mounts[key])
                assertNull("cycle $cycle was not disposed", disposes[key])
                assertEquals("unrelated row remains mounted", 1, mounts[unrelated.key])
                assertNull("unrelated row remains alive", disposes[unrelated.key])
            }
        }
    }

    @Test fun viewportObservesCollectedLiveRowsForNewestMessageAndDisplay() {
        var liveRows by mutableStateOf<List<ChatRenderItem>>(listOf(row("live-initial")))
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row("settled-1")))),
            MutableStateFlow(emptyList()),
            {},
        )
        compose.setContent {
            LettaChatTheme {
                val pages = presentation.settled.collectAsLazyPagingItems()
                val listState = rememberLazyListState()
                PagedTimelineLazyLayout.Viewport(
                    PagedTimelineViewportParams(
                        presentation = presentation,
                        state = ChatUiState(),
                        pages = pages,
                        displayedLive = liveRows,
                        live = liveRows,
                        listState = listState,
                        following = true,
                        onFollowingChange = {},
                        highlightedTarget = null,
                        routeTarget = null,
                        missingTarget = null,
                        appearance = ChatContentAppearance(),
                        callbacks = ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        ),
                        modifier = Modifier,
                    ),
                )
            }
        }
        compose.onNodeWithText("live-initial").assertIsDisplayed()
        compose.runOnIdle {
            liveRows = listOf(row("live-updated"))
        }
        compose.onNodeWithText("live-updated").assertIsDisplayed()
        compose.onNodeWithText("live-initial").assertDoesNotExist()
    }
}
