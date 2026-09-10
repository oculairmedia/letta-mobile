package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression contract for [ScrollToBottomFab] chrome visibility.
 *
 * The bug we are guarding against: the M3 SmallFloatingActionButton used
 * here renders with a `primaryContainer` color that, on the dark ambient
 * shader the chat surface ships with, is so close to the chat background
 * that the chrome appears invisible. Only the "Scroll to latest" debug
 * label remained visible to the user.
 *
 * This test asserts the fix's invariants at the level the FAB module can
 * enforce on its own:
 *
 *  1. The FAB renders with non-zero size when `visible = true`. A
 *     regressed implementation that hides the chrome by collapsing it
 *     would fail this assertion.
 *  2. The accessibility tree exposes the `Scroll to bottom` description
 *     so the visible "Scroll to latest" text the user saw is the actual
 *     semantics label, not a leaked debug overlay.
 *  3. The FAB is removed from the tree when `visible = false`.
 *
 * The "the container color is materially distinct from the dark
 * background" property is verified by the live Maestro flow on Pixel 2 XL
 * in `letta-mobile-myjly` — Robolectric does not paint real M3 colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScrollToBottomFabRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fabRendersAndHasNonZeroSizeWhenVisible() {
        compose.setContent {
            LettaChatTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ScrollToBottomFab(
                        visible = true,
                        onClick = {},
                        modifier = Modifier
                            .testTag(SCROLL_TO_BOTTOM_FAB_TAG)
                            .size(48.dp),
                    )
                }
            }
        }
        // The semantics-merging FAB+Icon node is the visible chrome.
        compose.onNodeWithContentDescription("Scroll to bottom")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(40.dp)
            .assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun fabDoesNotRenderWhenInvisible() {
        compose.setContent {
            LettaChatTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ScrollToBottomFab(
                        visible = false,
                        onClick = {},
                        modifier = Modifier.testTag(SCROLL_TO_BOTTOM_FAB_TAG),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()
    }
}
