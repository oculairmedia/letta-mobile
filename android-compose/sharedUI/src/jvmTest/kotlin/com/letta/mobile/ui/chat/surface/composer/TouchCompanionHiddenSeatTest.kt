@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1.9: the Touch page shows its companion above the bar only while the agent
 * works. Hidden (at once, under reduced motion), the seat collapses to nothing rather than going
 * unplaced: an unplaced seat left its last, whole bounds published, so the mascot layer went on
 * drawing the character over the bar and taking its taps.
 */
class TouchCompanionHiddenSeatTest {
    @Test
    fun aHiddenCompanionPublishesNoBoundsUnderReducedMotion() = runComposeUiTest {
        val port = CompanionTestPort()
        val shell = FakeMascotShell(COMPANION_TEST_AGENT)
        setTouchCompanionContent(port, shell)
        mainClock.advanceTimeBy(COMPANION_TEST_SETTLE_MILLIS)
        val key = MascotTransport.SeatKey(COMPANION_TEST_AGENT, MascotStage.COMPOSER_COMPANION)
        val working = shell.transport.seat(key)?.bounds
        assertTrue(working != null && working.width > 0f, "while the agent works the companion stands above the bar: $working")
        // The run ends: the companion goes at once.
        port.uiState.value = port.uiState.value.copy(isAgentTyping = false)
        mainClock.advanceTimeBy(COMPANION_TEST_SETTLE_MILLIS)
        val hidden = shell.transport.seat(key)?.bounds
        assertTrue(hidden == null || hidden.width <= 0f || hidden.height <= 0f, "the hidden companion still has bounds: $hidden")
    }
}
