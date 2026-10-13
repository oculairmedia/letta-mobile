@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.conversations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.ui.components.LettaRefreshTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** letta-mobile-ckmub.3.22: the conversations page reloads by a pull on touch, its refresh on pointer. */
class ConversationsPageRefreshTest {
    private var reloads = 0

    private val state = ConversationsPageMapping.state(
        ConversationsPageInput(
            conversations = (1..3).map { i ->
                ChatConversationSummary(
                    id = "c$i",
                    title = "Chat $i",
                    agentName = "Ada",
                    updatedAtLabel = "2026-10-12T11:00:00Z",
                    lastMessagePreview = "",
                    agentId = "a",
                )
            },
        ),
        Instant.parse("2026-10-12T12:00:00Z"),
    )

    private fun ComposeUiTest.show(host: LettaHost, page: ConversationsPageState = state) {
        setContent {
            MaterialTheme {
                ProvideLettaHost(host) {
                    Box(Modifier.size(width = 400.dp, height = 640.dp)) {
                        ConversationsPage(page, ConversationsPageActions(onReload = { reloads++ }))
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.pull() {
        onNodeWithTag(LettaRefreshTags.PULL).performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 600) }
        waitForIdle()
    }

    @Test
    fun onTouchAPullReloadsTheRosterWithNoRefreshButton() = runComposeUiTest {
        show(LettaHost.Touch)
        onNodeWithTag(ConversationsPageTags.REFRESH).assertDoesNotExist()
        pull()
        assertEquals(1, reloads)
    }

    @Test
    fun onTouchAPullReloadsAFailedPageToo() = runComposeUiTest {
        show(LettaHost.Touch, ConversationsPageState(error = "offline"))
        pull()
        assertEquals(1, reloads)
    }

    @Test
    fun onPointerTheFilterRowsRefreshReloads() = runComposeUiTest {
        show(LettaHost.Pointer)
        onNodeWithTag(LettaRefreshTags.PULL).assertDoesNotExist()
        onNodeWithTag(ConversationsPageTags.REFRESH).performClick()
        assertEquals(1, reloads)
    }
}
