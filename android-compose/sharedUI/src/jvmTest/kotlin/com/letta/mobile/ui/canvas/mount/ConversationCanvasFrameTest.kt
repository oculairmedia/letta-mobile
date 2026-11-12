@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.mount

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.canvas.mount.client.DeviceFallbackCanvasMountClient
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.chat.surface.LocalChatCanvasOnBoard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The board seat every host shares: its own board at once, the mount when the host has one, canvas mode reported. */
class ConversationCanvasFrameTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val mounted = CanvasId("canvas-b")

    @Test
    fun theMountedBoardReplacesTheOwnBoardAndCanvasModeIsReported() = runComposeUiTest {
        val mounts = TestHostMounts().apply { stored[target] = mounted }
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(mounts))
        var onBoard by mutableStateOf(true)
        setContent {
            ProvideConversationCanvasPicker(picker) {
                CompositionLocalProvider(LocalChatCanvasOnBoard provides onBoard) {
                    ConversationCanvasFrame(target) { shown -> Text(shown?.value ?: OWN) }
                }
            }
        }
        waitForIdle()
        onNodeWithText(mounted.value).assertExists()
        assertEquals(target, picker.focus.current.value)

        onBoard = false
        waitForIdle()
        assertNull(picker.focus.current.value)
        // Chat mode: the page is still on screen, so a pick still mounts on this conversation.
        assertEquals(target, picker.focus.onScreen.value)
    }

    @Test
    fun aDeviceMountSwapsTheBoardInPlaceAndLeavingThePageClearsTheFocus() = runComposeUiTest {
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(DeviceFallbackCanvasMountClient(CanvasMountClient.Unsupported)))
        var showing by mutableStateOf(true)
        setContent {
            ProvideConversationCanvasPicker(picker) {
                CompositionLocalProvider(LocalChatCanvasOnBoard provides true) {
                    if (showing) ConversationCanvasFrame(target) { shown -> Text(shown?.value ?: OWN) }
                }
            }
        }
        waitForIdle()
        onNodeWithText(OWN).assertExists()

        runBlocking { picker.pick(mounted) }
        waitForIdle()
        onNodeWithText(mounted.value).assertExists()

        showing = false
        waitForIdle()
        assertNull(picker.focus.onScreen.value)
        assertNull(picker.focus.current.value)
    }

    @Test
    fun withoutAPickerOrAConversationTheOwnBoardShows() = runComposeUiTest {
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(TestHostMounts()))
        setContent {
            ConversationCanvasFrame(target) { shown -> Text("first:" + (shown?.value ?: OWN)) }
            ProvideConversationCanvasPicker(picker) {
                ConversationCanvasFrame(null) { shown -> Text("second:" + (shown?.value ?: OWN)) }
            }
        }
        onNodeWithText("first:$OWN").assertExists()
        onNodeWithText("second:$OWN").assertExists()
    }

    private companion object {
        const val OWN = "own"
    }
}
