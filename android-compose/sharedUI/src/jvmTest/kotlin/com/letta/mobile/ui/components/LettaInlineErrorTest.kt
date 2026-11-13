@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one inline error pages show (letta-mobile-16b2u): retry and dismiss in place, the page's tag kept. */
class LettaInlineErrorTest {
    @Test
    fun dismissIsOfferedOnlyWhenTheCallerCanClearTheError() = runComposeUiTest {
        var dismissed = 0
        setContent {
            MaterialTheme {
                LettaInlineError("Couldn't delete the run", Modifier.testTag(PAGE_TAG), title = null, onDismiss = { dismissed++ })
            }
        }
        onNodeWithTag(LettaInlineErrorTags.RETRY).assertDoesNotExist()
        onNodeWithTag(LettaInlineErrorTags.DISMISS).assertTextEquals("Dismiss").performClick()
        assertEquals(1, dismissed)
        // The page's own tag and the shared one both find the error.
        onNodeWithTag(PAGE_TAG).assertExists()
        onNodeWithTag(LettaInlineErrorTags.ERROR).assertExists()
        onNodeWithText("Couldn't delete the run").assertExists()
    }

    @Test
    fun retryAloneDrawsNoDismiss() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                LettaInlineError("Backend unreachable", onRetry = { retries++ })
            }
        }
        onNodeWithTag(LettaInlineErrorTags.DISMISS).assertDoesNotExist()
        onNodeWithTag(LettaInlineErrorTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    private companion object {
        const val PAGE_TAG = "some-page-error"
    }
}
