@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/** Waits until session loading and the board's initial scene import have both completed. */
internal fun ComposeUiTest.awaitCanvasReady() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasContentDescription("Canvas board")).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Drives frames across UI transitions, even when the controller state already matches. */
internal fun ComposeUiTest.awaitCanvasUi(
    conditionDescription: String,
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    waitUntil(conditionDescription = conditionDescription, timeoutMillis = timeoutMillis) {
        mainClock.advanceTimeByFrame()
        condition()
    }
    waitForIdle()
}

/** Opens an item in the canvas workspace overflow menu. */
internal fun ComposeUiTest.openMenuItem(label: String) {
    onNodeWithContentDescription("More").performClick()
    onNodeWithText(label).performClick()
}
