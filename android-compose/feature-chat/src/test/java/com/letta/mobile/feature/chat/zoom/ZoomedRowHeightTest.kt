package com.letta.mobile.feature.chat.zoom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.letta.mobile.feature.chat.screen.messagelist.MeasuredChatRenderItem
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatMessageGeometryBucket
import com.letta.mobile.ui.chat.render.ChatRenderItemGeometrySignature
import com.letta.mobile.ui.theme.TimelineZoomScope
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-tgypm.1 follow-up, reported from the device: zooming OUT left a large empty band
 * between the last message and the composer.
 *
 * The row caches its measured height and applies it as a floor so scrolling does not re-measure.
 * That height belongs to the zoom it was measured at. While a pinch is running the signature still
 * describes the committed zoom, so the floor was the pre-pinch height: the text shrank inside a box
 * that could not, and the leftover box was the gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ZoomedRowHeightTest {
    @get:Rule val compose = createComposeRule()

    /** The bucket carries the COMMITTED scale, which is exactly why a live pinch cannot use it. */
    private fun signature() = ChatRenderItemGeometrySignature(
        bucket = ChatMessageGeometryBucket(
            renderKey = "row-1",
            widthPx = 1080,
            densityBucket = 1,
            fontScaleBucket = 1,
            chatFontScaleBucket = 1,
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            chatMode = "chat",
            expansionHash = 0,
        ),
        contentLength = 64,
        contentHash = 7,
    )

    /**
     * Content height is tied to the zoom explicitly rather than to rendered text, because
     * Robolectric draws with a synthetic font whose metrics barely move with size. This exercises
     * the floor itself, which is the thing that was wrong: the row must be free to shrink when the
     * zoom it is drawn at is not the zoom its cached height was measured at.
     */
    @Composable
    private fun Row(geometry: ChatMessageGeometryState, scale: Float, committed: Float, tag: String) {
        // The floor lives on MeasuredChatRenderItem's own Box, so the wrapper is what has to be
        // measured; the content inside is always free to shrink.
        Box(Modifier.testTag(tag)) {
        TimelineZoomScope(scale) {
            MeasuredChatRenderItem(
                signature = signature(),
                geometryState = geometry,
                applyCachedMinHeight = true,
                scaleIsTransient = scale != committed,
            ) {
                Box(Modifier.fillMaxWidth().height((40 * scale).dp))
            }
        }
        }
    }

    @Test fun aRowShrinksWithItsContentInsteadOfKeepingThePrePinchBox() {
        val geometry = ChatMessageGeometryState()
        val live = mutableStateOf(1f)
        compose.setContent {
            val scale by live
            Column { Row(geometry, scale, committed = 1f, tag = "row") }
        }
        compose.waitForIdle()
        val tall = compose.onNodeWithTag("row").fetchSemanticsNode().size.height
        assertTrue("row recorded no height to floor", tall > 0)

        live.value = 0.5f
        compose.waitForIdle()
        val short = compose.onNodeWithTag("row").fetchSemanticsNode().size.height
        assertTrue(
            "row kept its pre-pinch height while its content shrank: was $tall, still $short",
            short < tall,
        )
    }

    /**
     * Fail-on-revert, expressed as the difference the flag makes rather than by editing source: the
     * same row, same cached floor, same shrink - pinned when the scale is treated as settled, free
     * when it is treated as transient.
     */
    @Test fun theTransientFlagIsWhatFreesTheRow() {
        val geometry = ChatMessageGeometryState()
        val live = mutableStateOf(1f)
        compose.setContent {
            val scale by live
            Column {
                Box(Modifier.testTag("pinned")) {
                    TimelineZoomScope(scale) {
                        MeasuredChatRenderItem(
                            signature = signature(),
                            geometryState = geometry,
                            applyCachedMinHeight = true,
                            scaleIsTransient = false,
                        ) {
                            Box(Modifier.fillMaxWidth().height((40 * scale).dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val tall = compose.onNodeWithTag("pinned").fetchSemanticsNode().size.height
        live.value = 0.5f
        compose.waitForIdle()
        assertEquals(
            "with the scale treated as settled the stale floor must still pin the row",
            tall,
            compose.onNodeWithTag("pinned").fetchSemanticsNode().size.height,
        )
    }
}
