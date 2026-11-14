@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlin.test.Test
import kotlin.test.assertTrue

class LettaToggleRowTest {
    @Test
    fun theWholeRowIsOneSwitchAtLeastATouchTargetTall() {
        FOUNDATION_WIDTHS.forEach { width ->
            runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
                var checked by mutableStateOf(false)
                setFoundationContent(width) {
                    LettaToggleRow(
                        title = "Sync memory",
                        checked = checked,
                        onCheckedChange = { checked = it },
                        modifier = Modifier.testTag(ROW),
                        supporting = "Keeps blocks in step across devices",
                    )
                }
                val row = onNodeWithTag(ROW).assert(hasRole(Role.Switch)).assertIsOff().assertWidthIsEqualTo(width)
                row.performClick()
                row.assertIsOn()
                val height = row.getUnclippedBoundsInRoot().height
                assertTrue(height >= LettaControlTokens.minTouchTarget, "at $width the row is $height tall")
            }
        }
    }

    @Test
    fun aDisabledRowIgnoresTaps() = runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
        var checked by mutableStateOf(false)
        setFoundationContent {
            LettaToggleRow("Sync", checked, { checked = it }, Modifier.testTag(ROW), enabled = false)
        }
        onNodeWithTag(ROW).assertIsNotEnabled().performClick().assertIsOff()
    }

    private companion object {
        const val ROW = "row"
    }
}
