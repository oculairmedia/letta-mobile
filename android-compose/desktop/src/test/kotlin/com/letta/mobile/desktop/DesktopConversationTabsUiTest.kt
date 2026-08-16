@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConversationTabsUiTest {
    @Test
    fun activeTabExposesCloseActionWithoutPointerHover() = runComposeUiTest {
        var closedId: String? = null
        setContent {
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = listOf(DesktopConversationTab("conversation-1", "First", "Ada")),
                    activeConversationId = "conversation-1",
                    onSelect = {},
                    onClose = { closedId = it },
                )
            }
        }

        onNodeWithContentDescription("Close First tab").performClick()

        assertEquals("conversation-1", closedId)
    }
}
