@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.ui.chat.surface.ChatImageActions
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatImageActions
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1.23: the phone's image viewer gestures and actions (the legacy ChatImageViewer). */
class TouchImageViewerTest {
    private val container = Size(1000f, 2000f)
    private val images = listOf(
        UiImageAttachment(base64 = "", mediaType = "image/png"),
        UiImageAttachment(base64 = "", mediaType = "image/jpeg"),
    )

    @Test
    fun doubleTapZoomsToTwoAndAHalfAndBack() {
        val zoomed = doubleTapImageTransform(ImageTransformState(), Offset(500f, 1000f), container)
        assertEquals(DoubleTapImageScale, zoomed.scale)
        assertEquals(ImageTransformState(), doubleTapImageTransform(zoomed, Offset(500f, 1000f), container))
    }

    @Test
    fun pinchIsClampedToFiveTimesAndPanStaysInsideTheImage() {
        val pinched = applyImageTransformGesture(ImageTransformState(), ImageGestureFrame(10f, Offset.Zero, Offset(500f, 1000f)), container)
        assertEquals(MaxImageScale, pinched.scale)
        val panned = applyImageTransformGesture(pinched, ImageGestureFrame(1f, Offset(1e6f, -1e6f), Offset(500f, 1000f)), container)
        // At 5x the image overhangs by 2x its size on each side.
        assertEquals(Offset(2000f, -4000f), panned.offset)
        // Pinching back under 1x snaps to rest.
        assertEquals(ImageTransformState(), applyImageTransformGesture(pinched, ImageGestureFrame(0.1f, Offset.Zero, Offset.Zero), container))
    }

    @Test
    fun onlyAVerticalSwipeAtRestDismisses() {
        assertTrue(shouldDismissImageViewer(scale = 1f, verticalDragDistance = 200f))
        assertTrue(shouldDismissImageViewer(scale = 1f, verticalDragDistance = -200f))
        assertFalse(shouldDismissImageViewer(scale = 1f, verticalDragDistance = 100f))
        assertFalse(shouldDismissImageViewer(scale = 2f, verticalDragDistance = 400f))
    }

    @Test
    fun theTopBarSharesAndSavesTheImageOnScreen() = runComposeUiTest {
        val shared = mutableListOf<UiImageAttachment>()
        val saved = mutableListOf<UiImageAttachment>()
        var dismissed = false
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatPlatformStyle provides ChatPlatformStyle.Touch,
                    LocalChatImageActions provides ChatImageActions(save = { saved += it }, share = { shared += it }),
                ) {
                    ChatImageViewerContent(images = images, initialIndex = 1, onDismiss = { dismissed = true })
                }
            }
        }
        onNodeWithContentDescription("Share image").performClick()
        onNodeWithContentDescription("Save image").performClick()
        runOnIdle {
            assertEquals(listOf(images[1]), shared)
            assertEquals(listOf(images[1]), saved)
        }
        onNodeWithContentDescription("Close image viewer").performClick()
        runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun doubleTappingAPageZoomsIt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Touch) {
                    ChatImageViewerContent(images = images.take(1), initialIndex = 0, onDismiss = {})
                }
            }
        }
        val page = onNode(SemanticsMatcher.keyIsDefined(ChatImageViewerScaleKey))
        page.performTouchInput { doubleClick() }
        waitForIdle()
        onNode(SemanticsMatcher.expectValue(ChatImageViewerScaleKey, DoubleTapImageScale)).assertExists()
    }
}
