@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasLocalPresenceTest {
    private class RecordingTransport : CanvasPresenceTransport {
        val updates = mutableListOf<Pair<CanvasId, CanvasPresence>>()
        override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) {
            updates += canvasId to presence
        }
        override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> = flowOf(emptyList())
    }

    @Test
    fun pointerPublishesWorldCoordinatesAndDisposeLeavesWithoutTakingClicks() = runComposeUiTest {
        val transport = RecordingTransport()
        val canvasId = CanvasId("board")
        var mounted by mutableStateOf(true)
        var clicks = 0
        setContent {
            if (mounted) {
                Box(Modifier.size(200.dp).testTag("board")
                    .canvasLocalPresence(transport, canvasId, "local-peer") { (it - Offset(10f, 20f)) / 2f }
                    .clickable { clicks++ })
            }
        }
        onNodeWithTag("board").performMouseInput { moveTo(Offset(50f, 80f)) }
        mainClock.advanceTimeBy(150)
        runOnIdle {
            val update = transport.updates.last()
            assertEquals(canvasId, update.first)
            assertEquals("local-peer", update.second.peerId)
            assertEquals(20f, update.second.cursorX)
            assertEquals(30f, update.second.cursorY)
            assertTrue(update.second.isActive)
        }
        onNodeWithTag("board").performClick()
        runOnIdle {
            assertEquals(1, clicks)
            mounted = false
        }
        waitForIdle()
        runOnIdle { assertFalse(transport.updates.last().second.isActive) }
    }
}
