package com.letta.mobile.ui.canvas

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens the Canvas screen on an Android runtime, which nothing did before: the screen compiled and
 * shipped, then died on first open with
 * `MissingResourceException: composeResources/io.ak1.drawbox.ui.resources/drawable/undo.xml`
 * (letta-mobile-r5f3r). `io.ak1:drawbox-ui:0.0.1-alpha01`'s Android AAR carries classes but no
 * `composeResources`, so its ControlsBar can never find its own drawables on Android; only a test
 * that composes the screen on Android catches that, since it is a packaging fact, not a type error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class CanvasWorkspaceAndroidRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun canvasWorkspaceOpensAndShowsItsToolsWithoutMissingResources() {
        composeRule.setLettaTestContent { CanvasWorkspace() }
        composeRule.waitForIdle()

        // The tool bar rendered: every icon comes from this module's own icon set. The bar scrolls
        // horizontally, so only the leading tools are on screen at this width; the rest must exist.
        composeRule.onNodeWithContentDescription("Select").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Draw").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Eraser").assertExists()
        composeRule.onNodeWithContentDescription("Undo").assertExists()
        composeRule.onNodeWithContentDescription("Redo").assertExists()
    }
}
