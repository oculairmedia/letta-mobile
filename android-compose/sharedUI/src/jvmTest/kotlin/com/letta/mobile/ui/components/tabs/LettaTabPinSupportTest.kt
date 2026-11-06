@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.components.tabs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LettaTabPinSupportTest {
    @Test
    fun tabsWithoutPinSupportDoNotShowAnInertPinControl() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LettaTabStrip(listOf(LettaTab("subagent", "Worker")), "subagent", LettaTabStripActions())
            }
        }
        onNodeWithContentDescription("Unpin Worker tab").assertDoesNotExist()
    }

    @Test
    fun pinnedTabsCanReorderAcrossAnInterveningPreview() = runComposeUiTest {
        var moved: Pair<String, Int>? = null
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = listOf(LettaTab("a", "First"), LettaTab("b", "Preview", isTransient = true), LettaTab("c", "Third")),
                    activeKey = "a",
                    actions = LettaTabStripActions(onReorder = { key, index -> moved = key to index }),
                )
            }
        }
        onNodeWithText("Third").performMouseInput {
            moveTo(center)
            press()
            repeat(4) { moveBy(Offset(x = -100f, y = 0f)) }
            release()
        }
        waitForIdle()
        assertEquals("c" to 0, moved)
    }

}
