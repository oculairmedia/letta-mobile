@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAotTrainingExitTest {
    @Test
    fun aTrainingRunExitsOnceStartupHasSettled() = runComposeUiTest {
        mainClock.autoAdvance = false
        var exits = 0
        setContent { DesktopAotTrainingExit(training = true) { exits++ } }

        mainClock.advanceTimeBy(AOT_TRAINING_SETTLE_MS / 2)
        assertEquals(0, exits, "exited before startup settled")
        mainClock.advanceTimeBy(AOT_TRAINING_SETTLE_MS)
        assertEquals(1, exits)
    }

    @Test
    fun aNormalLaunchNeverExits() = runComposeUiTest {
        mainClock.autoAdvance = false
        var exits = 0
        setContent { DesktopAotTrainingExit(training = false) { exits++ } }

        mainClock.advanceTimeBy(AOT_TRAINING_SETTLE_MS * 2)
        assertEquals(0, exits)
    }
}
