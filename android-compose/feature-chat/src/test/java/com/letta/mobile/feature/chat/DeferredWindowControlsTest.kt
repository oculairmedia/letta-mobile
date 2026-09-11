package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.letta.mobile.feature.chat.screen.DeferredWindowControls
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeferredWindowControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitReadReplacesWindowAndPreviousRestoresOffset() {
        val offsets = mutableListOf<Long>()
        compose.setContent {
            DeferredWindowControls("row") { offset ->
                offsets += offset
                TimelineSemanticWindowResult.Text("window-$offset", if (offset == 0L) 10L else null, 20, 8, 1)
            }
        }
        compose.runOnIdle { org.junit.Assert.assertTrue(offsets.isEmpty()) }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        // The page number is derived from history depth, so it always describes the offset shown.
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next page").performClick()
        compose.onNodeWithText("window-10").assertIsDisplayed()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous page").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf(0L, 10L, 0L), offsets) }
    }

    @Test fun theLastPageOffersNoNextAndTheFirstOffersNoPrevious() {
        compose.setContent {
            DeferredWindowControls("row") { offset ->
                TimelineSemanticWindowResult.Text("window-$offset", null, 20, 8, 1)
            }
        }
        compose.onNodeWithText("View content").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        // A single-page body still reads as a page, but neither direction is offered.
        compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
    }
}
