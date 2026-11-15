@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingToolAnnouncementTest {
    @Test
    fun announcesEachNewPendingToolOnce() = runComposeUiTest {
        var pendingIds by mutableStateOf(listOf("tool-1", "tool-2"))
        var announcements = 0

        setContent {
            PendingToolAnnouncementEffect(pendingToolIds = pendingIds) { announcements++ }
        }

        waitForIdle()
        assertEquals(2, announcements)

        pendingIds = listOf("tool-1", "tool-2")
        waitForIdle()
        assertEquals(2, announcements)

        pendingIds = listOf("tool-1", "tool-2", "tool-3")
        waitForIdle()
        assertEquals(3, announcements)
    }
}
