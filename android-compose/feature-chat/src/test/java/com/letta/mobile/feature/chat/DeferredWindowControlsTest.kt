package com.letta.mobile.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText("Next window").performClick()
        compose.onNodeWithText("window-10").assertIsDisplayed()
        compose.onNodeWithText("Previous window").performClick()
        compose.onNodeWithText("window-0").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf(0L, 10L, 0L), offsets) }
    }
}
