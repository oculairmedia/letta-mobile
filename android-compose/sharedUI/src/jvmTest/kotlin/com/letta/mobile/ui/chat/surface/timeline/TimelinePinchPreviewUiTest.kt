@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalRowComposed
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: a pinch previews in the list's draw layer and re-lays out the rows once,
 * on release. Re-laying every row out at each gesture frame was the shared page's pinch flicker.
 */
class TimelinePinchPreviewUiTest {

    private val messages = (0 until 6).map { i ->
        UiMessage(
            id = "m$i",
            role = if (i % 2 == 0) "user" else "assistant",
            content = "message $i",
            timestamp = "2026-09-12T12:00:%02dZ".format(i),
        )
    }

    /** Row compositions by item key, since the last [clear]. */
    private val composed = mutableMapOf<String, Int>()

    /** The owner's committed scale; setFontScale stores it, as the page's host does. */
    private var appearance by mutableStateOf(ChatSurfaceAppearance())

    private val actions = object : ChatActions by RecordingChatActions() {
        override fun setFontScale(scale: Float) {
            appearance = appearance.copy(fontScale = scale)
        }
    }

    private fun ComposeUiTest.show() {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRowComposed provides { key -> composed[key] = (composed[key] ?: 0) + 1 }) {
                    Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                        ChatTimeline(
                            state = ChatUiState(
                                conversationState = ConversationState.Ready("c1"),
                                isLoadingMessages = false,
                                messages = messages.toPersistentList(),
                            ),
                            pagedTimeline = null,
                            actions = actions,
                            capabilities = ChatSurfaceCapabilities.Default,
                            host = ChatSurfaceHost(),
                            appearance = appearance,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.listBounds(): Rect = onNodeWithTag(ChatTimelineTags.LIST).fetchSemanticsNode().boundsInRoot

    @Test
    fun aPinchScalesOnlyTheListLayerThenRelaysOutTheRowsOnceOnRelease() = runComposeUiTest {
        show()
        val resting = listBounds()
        val centre = resting.center - resting.topLeft
        composed.clear()

        // Two fingers 200px apart close to 160px over four frames: a pinch to 80%.
        onNodeWithTag(ChatTimelineTags.LIST).performTouchInput {
            down(0, centre - Offset(SPAN / 2, 0f))
            down(1, centre + Offset(SPAN / 2, 0f))
        }
        for (step in 1..FRAMES) {
            val half = (SPAN - (SPAN - SPAN * TARGET) * step / FRAMES) / 2
            onNodeWithTag(ChatTimelineTags.LIST).performTouchInput {
                updatePointerTo(0, centre - Offset(half, 0f))
                updatePointerTo(1, centre + Offset(half, 0f))
                move()
            }
            waitForIdle()
        }

        assertEquals(emptyMap(), composed, "a pinch frame recomposed rows")
        onNodeWithTag(ChatTimelineTags.FONT_SCALE).assertExists()
        val pinched = listBounds()
        assertEquals(resting.width * TARGET, pinched.width, 1f, "the layer follows the gesture")
        assertEquals(resting.bottom, pinched.bottom, 1f, "the layer scales from the anchored bottom edge")

        onNodeWithTag(ChatTimelineTags.LIST).performTouchInput {
            up(0)
            up(1)
        }
        waitForIdle()

        assertTrue(composed.isNotEmpty())
        assertEquals(composed.mapValues { 1 }, composed, "each row re-lays out exactly once, on release")
        assertEquals(TARGET, appearance.fontScale, 0.0001f, "the snapped scale is committed once")
        onNodeWithTag(ChatTimelineTags.FONT_SCALE).assertDoesNotExist()
        assertEquals(resting.width, listBounds().width, 1f, "the layer is back at rest")
    }

    private companion object {
        const val SPAN = 200f
        const val TARGET = 0.8f
        const val FRAMES = 4
    }
}
