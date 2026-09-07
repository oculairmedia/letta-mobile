package com.letta.mobile.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
