@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Settings page's Reset layout row: drawn only while the shell hands it a reset, and running it. */
class LayoutSettingsSectionTest {
    @Test
    fun withoutAResetTheRowIsLeftOut() = runComposeUiTest {
        setContent { Frame { LayoutSettingsSection() } }

        onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + "layout").assertDoesNotExist()
    }

    @Test
    fun theRowRunsTheShellsReset() = runComposeUiTest {
        var resets = 0
        setContent {
            Frame { CompositionLocalProvider(LocalShellLayoutReset provides { resets++ }) { LayoutSettingsSection() } }
        }

        onNodeWithText(SettingsCopy.RESET_LAYOUT.title).performClick()

        assertEquals(1, resets)
    }
}
