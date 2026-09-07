package com.letta.mobile.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.paging.PagingData
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.*
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PagedChatContentTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String) = ChatRenderItem.Single(
        UiMessage(id = id, role = "user", content = id, timestamp = "2026-09-07T00:00:00Z"),
        GroupPosition.None,
    )

    @Test fun targetLookupIncludesLiveAndPlaceholderOffsetsWithoutLoadingMissingHistory() {
        val rows = listOf(row("newer"), row("target"), row("older"))
        org.junit.Assert.assertEquals(8, residentTargetIndex(rows, "target", 2, 5))
        org.junit.Assert.assertNull(residentTargetIndex(rows, "missing", 2, 5))
    }

    @Test fun dateBoundaryRequiresResidentOlderRowAndValidDifferentDay() {
        val newer = row("newer")
        val older = row("older").copy(message = row("older").message.copy(timestamp = "2026-09-06T23:00:00Z"))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 9, 7), pagedBoundaryDate(newer, older))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer, null))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer, row("same day")))
        org.junit.Assert.assertNull(pagedBoundaryDate(newer.copy(message = newer.message.copy(timestamp = "invalid")), older))
    }

    @Test fun missingTargetFeedbackRequiresEngineConfirmation() {
        val missing = MutableStateFlow<String?>(null)
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row("unrelated")))), MutableStateFlow(emptyList()), {}, missing,
        )
        compose.setContent {
            LettaChatTheme {
                CompositionLocalProvider(LocalChatPagingPresentation provides presentation) {
                    ChatContent(
                        state = ChatUiState(),
                        callbacks = ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {},
                            onLoadOlderMessages = { error("Must not search sequentially") },
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        ),
                        appearance = ChatContentAppearance(scrollToMessageId = "target"),
                    )
                }
            }
        }
        compose.onNodeWithText("Message not found").assertDoesNotExist()
        compose.runOnIdle { missing.value = "stale target" }
        compose.onNodeWithText("Message not found").assertDoesNotExist()
        compose.runOnIdle { missing.value = "target" }
        compose.onNodeWithText("Message not found").assertIsDisplayed()
    }

    @Test fun targetViewportDoesNotFollowLiveUpdatesUntilLatestRequested() {
        val live = MutableStateFlow<List<ChatRenderItem>>(emptyList())
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from((0..80).map { row("row-$it") })), live, {},
        )
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                    ), ChatContentAppearance(scrollToMessageId = "row-50"),
                )
            }
        }
        compose.onNodeWithText("row-50").assertIsDisplayed()
        compose.runOnIdle { live.value = listOf(row("live-new")) }
        compose.onNodeWithText("row-50").assertIsDisplayed()
        compose.onNodeWithText("Scroll to latest").performClick()
        compose.onNodeWithText("live-new").assertIsDisplayed()
        compose.runOnIdle { live.value = listOf(row("live-updated")) }
        compose.onNodeWithText("live-updated").assertIsDisplayed()
    }

    @Test fun pinchCommitsScaleThroughBothHostCallbacks() {
        var active = 1f
        var persisted = 1f
        val presentation = ChatPagingPresentation(
            flowOf(PagingData.from(listOf(row("message")))), MutableStateFlow(emptyList()), {},
        )
        compose.setContent {
            LettaChatTheme {
                PagedChatMessageList(
                    presentation, ChatUiState(),
                    ChatContentCallbacks(
                        onSendMessage = {}, onRerunMessage = {}, onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {}, onAttachmentImageTap = null,
                        onActiveFontScaleChange = { active = it }, onFontScaleChange = { persisted = it },
                    ), ChatContentAppearance(),
                )
            }
        }
        compose.onRoot().performTouchInput {
            pinch(Offset(center.x - 30, center.y), Offset(center.x + 30, center.y),
                Offset(center.x - 70, center.y), Offset(center.x + 70, center.y))
        }
        compose.runOnIdle {
            org.junit.Assert.assertTrue(active > 1f)
            org.junit.Assert.assertEquals(active, persisted)
            org.junit.Assert.assertTrue(active <= 1.6f)
        }
    }

    @Test fun hostIsDisabledWithoutEngineBinding() {
        org.junit.Assert.assertNull(ChatPagingHost().select)
    }

    @Test fun flaggedContentRendersPagedAndLiveRowsInsteadOfLegacyMessages() {
        val live = MutableStateFlow<List<ChatRenderItem>>(listOf(row("live row")))
        val presentation = ChatPagingPresentation(flowOf(PagingData.from(listOf(row("settled row")))), live, {})
        compose.setContent {
            LettaChatTheme {
                CompositionLocalProvider(LocalChatPagingPresentation provides presentation) {
                    ChatContent(
                        state = ChatUiState(messages = persistentListOf(row("legacy row").message)),
                        callbacks = ChatContentCallbacks(
                            onSendMessage = {}, onRerunMessage = {},
                            onLoadOlderMessages = { error("Legacy pager must not run") },
                            onSubmitApproval = { _, _, _, _ -> }, onToggleRunCollapsed = {},
                            onToggleReasoningExpanded = {}, onDismissA2uiSurface = {}, onAttachmentImageTap = null,
                        ),
                        appearance = ChatContentAppearance(),
                    )
                }
            }
        }
        compose.onNodeWithText("live row").assertIsDisplayed()
        compose.onNodeWithText("settled row").assertIsDisplayed()
        compose.onNodeWithText("legacy row").assertDoesNotExist()
        compose.runOnIdle { live.value = listOf(row("updated live row")) }
        compose.onNodeWithText("updated live row").assertIsDisplayed()
        compose.onNodeWithText("settled row").assertIsDisplayed()
    }
}
