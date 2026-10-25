@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasContentDescription

/** Waits until session loading and the board's initial scene import have both completed. */
internal fun ComposeUiTest.awaitCanvasReady() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodes(hasContentDescription("Canvas board")).fetchSemanticsNodes().isNotEmpty()
    }
}
