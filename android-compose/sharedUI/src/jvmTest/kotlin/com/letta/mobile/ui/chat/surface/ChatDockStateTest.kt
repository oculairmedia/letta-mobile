@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.1: the dock's working copy of the host's placement. A placement the host
 * hands over late is the one the very composition that receives it reads (never the default
 * for a frame), and the host handing back what the dock reported does not pull a gesture back.
 */
class ChatDockStateTest {
    @Test
    fun aLatePlacementIsReadInTheCompositionThatReceivesIt() = runComposeUiTest {
        var host by mutableStateOf(ChatDockGeometry.Default)
        val seen = mutableListOf<Pair<ChatDockGeometry, ChatDockGeometry>>()
        setContent {
            val state = rememberChatDockState(host) {}
            seen += host to state.geometry
        }
        waitForIdle()
        val saved = ChatDockGeometry(anchorX = 0.1f, anchorY = 0.2f, widthDp = 420f, heightDp = 380f)
        host = saved
        waitForIdle()
        val afterArrival = seen.filter { it.first == saved }
        assertEquals(listOf(saved), afterArrival.map { it.second }.distinct())
    }

    @Test
    fun aLateEchoOfAnEarlierReportDoesNotPullTheDockBack() = runComposeUiTest {
        var host by mutableStateOf(ChatDockGeometry.Default)
        lateinit var state: ChatDockState
        setContent { state = rememberChatDockState(host) {} }
        waitForIdle()
        val first = ChatDockGeometry(anchorX = 0f)
        runOnIdle {
            state.placeHead(0f, 1f)
            state.placeHead(1f, 0.5f)
        }
        // The host persists, and hands back, the first move only after the second was made.
        host = first
        waitForIdle()
        assertEquals(ChatDockGeometry(anchorX = 1f, anchorY = 0.5f), state.geometry)
        // A change of its own still lands.
        host = ChatDockGeometry(anchorX = 0.3f, anchorY = 0.3f)
        waitForIdle()
        assertEquals(ChatDockGeometry(anchorX = 0.3f, anchorY = 0.3f), state.geometry)
    }
}
