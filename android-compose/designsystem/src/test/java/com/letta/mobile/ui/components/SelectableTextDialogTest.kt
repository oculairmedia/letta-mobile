package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class SelectableTextDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentStartsFullySelectedAndCloseDismisses() {
        var dismissals = 0
        val content = "A message worth copying"

        composeRule.setContent {
            MaterialTheme {
                SelectableTextDialog(
                    title = "Select message text",
                    text = content,
                    closeText = "Close",
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeRule.onNodeWithText("Select message text").assertExists()
        composeRule.onNodeWithText(content)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.TextSelectionRange,
                    TextRange(0, content.length),
                ),
            )
        composeRule.onNodeWithText("Close")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }
}
