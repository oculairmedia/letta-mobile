@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineSnapshot
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineTags
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.1 (hard cut S4): on a phone the thinking token sits over the composer bar, as
 * the legacy composer drew it, not as a timeline row; a pointer host (no composer companion without
 * a mascot) keeps the timeline row.
 */
class ComposerThinkingPlacementTest {
    private val thinking = ChatUiState(
        conversationState = ConversationState.Ready("c1"),
        isLoadingMessages = false,
        isStreaming = true,
        isAgentTyping = true,
        agentId = "agent-without-mascot",
        messages = persistentListOf(UiMessage(id = "u1", role = "user", content = "update the changelog", timestamp = "2026-10-11T16:06:00Z")),
    )

    private fun ComposeUiTest.page(style: ChatPlatformStyle) = setContent {
        MaterialTheme {
            Column(Modifier.size(400.dp, 600.dp)) {
                ChatTimelineSnapshot(
                    state = thinking,
                    actions = RecordingChatActions(),
                    appearance = ChatSurfaceAppearance(platformStyle = style),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (style == ChatPlatformStyle.Touch) ComposerThinkingSnapshot(thinking, Modifier.fillMaxWidth())
            }
        }
    }

    @Test
    fun touchComposerCarriesTheThinkingTokenAtThePageFoot() = runComposeUiTest {
        page(ChatPlatformStyle.Touch)
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        onNodeWithTag(ChatTimelineTags.THINKING).assertDoesNotExist()
        val token = onNodeWithTag(ComposerTestTags.TOUCH_COMPANION_STATUS).assertExists().getBoundsInRoot()
        // The token's own inset (xs) is all that separates it from the page's foot.
        assertTrue(token.bottom >= 590.dp, "the token sits at the foot, over the composer: ${token.bottom}")
    }

    @Test
    fun pointerTimelineKeepsItsThinkingRow() = runComposeUiTest {
        page(ChatPlatformStyle.Pointer)
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        onNodeWithTag(ChatTimelineTags.THINKING).assertExists()
    }

    private companion object {
        const val SETTLE_MILLIS = 1_000L
    }
}
