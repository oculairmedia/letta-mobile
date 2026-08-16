@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.ContextWindowOverview
import kotlin.test.Test

/**
 * Render coverage for the composer's context-window chip. The breakdown MODEL
 * (ordering, free space, formatting) is tested in `sharedLogic` commonTest
 * (ContextWindowUsageTest); this file only covers what the chip shows and what
 * the popover reveals.
 */
class DesktopComposerContextUsageUiTest {
    private val usage = ContextWindowUsage.from(
        ContextWindowOverview(
            contextWindowSizeMax = 1_000_000,
            contextWindowSizeCurrent = 50_200,
            numTokensSystem = 4_300,
            numTokensFunctionsDefinitions = 16_300,
            numTokensMessages = 15_200,
        ),
    )

    @Test
    fun chipShowsTheUsedShareAndOpensTheBreakdown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ComposerContextChip(ContextWindowUsageState(usage = usage))
            }
        }

        onNodeWithText("Context 5%").performClick()

        onNodeWithText("Context window").assertExists()
        onNodeWithText("50.2k / 1M (5%)").assertExists()
        onNodeWithText("Tool definitions").assertExists()
        onNodeWithText("Free space").assertExists()
    }

    @Test
    fun chipReportsAFailedReadingInsteadOfATotal() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ComposerContextChip(ContextWindowUsageState(error = "Backend unreachable."))
            }
        }

        onNodeWithText("Context —").performClick()

        onNodeWithText("Backend unreachable.").assertExists()
    }
}
