package com.letta.mobile.feature.chat

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.letta.mobile.feature.chat.screen.DismissibleA2uiSurface
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DismissibleA2uiSurfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `long press on surface opens delete dropdown and clicking it triggers dismiss callback`() {
        var dismissCalledWithId: String? = null
        val testSurfaceId = "test-surface-id"

        composeTestRule.setContent {
            DismissibleA2uiSurface(
                surfaceId = testSurfaceId,
                onDismissSurface = { dismissCalledWithId = it }
            ) {
                Text(
                    text = "Surface Content",
                    modifier = Modifier.testTag("surface_content")
                )
            }
        }

        // Initially dropdown shouldn't be there
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()

        // Long press on the content to open the dropdown menu
        composeTestRule.onNodeWithTag("surface_content").performTouchInput {
            longClick()
        }

        // Now Delete should be displayed
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()

        // Click on the Delete item
        composeTestRule.onNodeWithText("Delete").performClick()

        // Verify that the callback was invoked with the correct surface ID
        assertEquals(testSurfaceId, dismissCalledWithId)

        // Dropdown should be dismissed now
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
    }
}
