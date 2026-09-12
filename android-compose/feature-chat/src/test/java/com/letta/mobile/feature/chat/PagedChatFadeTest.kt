package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.PagedChatMessageList
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("integration")
class PagedChatFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun populatedPagedListDissolvesTopEdgeIntoLightChatBackground() {
        assertTopFadeOnRenderedMessage(Color(0xFFFFE0E0))
    }

    @Test
    fun populatedPagedListDissolvesTopEdgeIntoDarkChatBackground() {
        assertTopFadeOnRenderedMessage(Color(0xFF240018))
    }

    @Test
    fun scrollingAwayFromNewestPageDissolvesBottomEdgeOnRenderedMessage() {
        val fixture = setPagedContent(Color(0xFF240018), pageRole = "user")
        compose.mainClock.advanceTimeBy(FadeDurationMillis)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(FadeDurationMillis)

        val image = compose.onRoot().captureToImage()
        assertTrue(
            "The bottom fade must reduce the rendered user bubble colour at the center of the visible bottom edge",
            image.edgePixelAt(CenterX, image.height - EdgeInsetPx)
                .distanceTo(fixture.background) < MaxFadedDistance,
        )
        assertTrue(
            "The visible message above the bottom fade must retain its rendered colour",
            image.edgePixelAt(CenterX, image.height - UnfadedInsetPx)
                .distanceTo(fixture.background) > MinUnfadedDistance,
        )
    }

    @Test
    fun liveUserRowMovesIntoSettledPageWithoutObscuringNewestEdge() {
        val background = Color(0xFF240018)
        val live = MutableStateFlow<List<ChatRenderItem>>(listOf(row("handoff-user", role = "user")))
        val settled = MutableStateFlow<PagingData<ChatRenderItem>>(PagingData.from((0..40).map { row("page-$it") }))
        setPagedContent(background, live = live, settled = settled)
        compose.mainClock.advanceTimeBy(FadeDurationMillis)

        val whileLive = compose.onRoot().captureToImage()
        assertTrue(whileLive.edgePixelAt(CenterX, whileLive.height - EdgeInsetPx).distanceTo(background) > MinUnfadedDistance)

        compose.runOnIdle {
            live.value = emptyList()
            settled.value = PagingData.from(listOf(row("handoff-user", role = "user")) + (0..40).map { row("page-$it") })
        }
        compose.mainClock.advanceTimeBy(FadeDurationMillis)

        val afterSettlement = compose.onRoot().captureToImage()
        assertTrue(afterSettlement.edgePixelAt(CenterX, afterSettlement.height - EdgeInsetPx).distanceTo(background) > MinUnfadedDistance)
    }

    private fun assertTopFadeOnRenderedMessage(background: Color) {
        val fixture = setPagedContent(background, pageRole = "user")
        compose.mainClock.advanceTimeBy(FadeDurationMillis)
        val image = compose.onRoot().captureToImage()

        // x=CenterX lies in the wide user bubble, not in the list's side padding.
        // Without ChatFadingEdgesBox this pixel retains the bubble colour and exceeds the faded threshold.
        assertTrue(
            "The top fade must dissolve the user bubble at the rendered center, not merely expose side padding",
            image.edgePixelAt(CenterX, EdgeInsetPx).distanceTo(fixture.background) < MaxFadedDistance,
        )
        assertTrue(
            "The assistant bubble below the fade must remain visibly distinct from the chat background",
            image.edgePixelAt(CenterX, UnfadedInsetPx).distanceTo(fixture.background) > MinUnfadedDistance,
        )
    }

    private fun setPagedContent(
        background: Color,
        live: MutableStateFlow<List<ChatRenderItem>> = MutableStateFlow(emptyList()),
        pageRole: String = "assistant",
        settled: MutableStateFlow<PagingData<ChatRenderItem>> = MutableStateFlow(
            PagingData.from((0..40).map { row("page-$it", role = pageRole) }),
        ),
    ): Fixture {
        val presentation = ChatPagingPresentation(settled, live, {})
        compose.setContent {
            LettaChatTheme {
                Box(
                    modifier = Modifier
                        .size(360.dp)
                        .background(background),
                ) {
                    PagedChatMessageList(
                        presentation = presentation,
                        state = ChatUiState(isStreaming = live.value.isNotEmpty()),
                        callbacks = callbacks,
                        appearance = ChatContentAppearance(
                            chatBackground = ChatBackground.SolidColor(background, "test"),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        return Fixture(background)
    }

    private fun row(id: String, role: String = "assistant") = ChatRenderItem.Single(
        UiMessage(
            id = id,
            role = role,
            content = "Paged message $id. This deliberately spans a wide assistant bubble for an edge-pixel assertion.",
            timestamp = "2026-09-07T00:00:00Z",
        ),
        GroupPosition.None,
    )

    private fun ImageBitmap.edgePixelAt(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun Color.distanceTo(other: Color): Float =
        kotlin.math.abs(red - other.red) +
            kotlin.math.abs(green - other.green) +
            kotlin.math.abs(blue - other.blue)

    private data class Fixture(val background: Color)

    private companion object {
        const val CenterX = 180
        const val EdgeInsetPx = 8
        const val UnfadedInsetPx = 96
        const val FadeDurationMillis = 350L
        const val MaxFadedDistance = 0.20f
        const val MinUnfadedDistance = 0.35f

        val callbacks = ChatContentCallbacks(
            onSendMessage = {},
            onRerunMessage = {},
            onLoadOlderMessages = {},
            onSubmitApproval = { _, _, _, _ -> },
            onToggleRunCollapsed = {},
            onToggleReasoningExpanded = {},
            onAttachmentImageTap = null,
        )
    }
}
