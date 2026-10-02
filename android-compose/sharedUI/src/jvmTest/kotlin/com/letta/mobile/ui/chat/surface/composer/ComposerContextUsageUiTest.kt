@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.model.ContextWindowOverview
import kotlin.test.Test

/** Ported from desktop's DesktopComposerContextUsageUiTest: what the chip shows and reveals. */
class ComposerContextUsageUiTest {
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
        setContent { MaterialTheme { ComposerContextChip(ContextWindowUsageState(usage = usage)) } }

        onNodeWithText("Context 5%").performClick()

        onNodeWithText("Context window").assertExists()
        onNodeWithText("50.2k / 1M (5%)").assertExists()
        onNodeWithText("Tool definitions").assertExists()
        onNodeWithText("Free space").assertExists()
    }

    @Test
    fun chipReportsAFailedReadingInsteadOfATotal() = runComposeUiTest {
        setContent { MaterialTheme { ComposerContextChip(ContextWindowUsageState(error = "Backend unreachable.")) } }

        onNodeWithText("Context —").performClick()

        onNodeWithText("Backend unreachable.").assertExists()
    }
}
