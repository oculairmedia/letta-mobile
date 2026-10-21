@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.launch
import kotlin.test.Test

/** The real workspace publishes this device's cursor to its peers (letta-mobile-282sx). */
class CanvasWorkspacePresenceUiTest {

    @Test
    fun canvasWorkspace_publishesLocalCursorUnderTheHostIdentityAndLeavesOnClose() = runComposeUiTest {
        val presenceTransport = com.letta.mobile.data.canvas.InMemoryCanvasPresenceTransport()
        val canvasId = com.letta.mobile.data.model.CanvasId("local-presence-canvas")
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(
                store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore(),
                options = com.letta.mobile.data.canvas.CanvasCreateOptions(canvasId = canvasId, title = "Local Presence"),
            )
        }
        val seen = java.util.concurrent.CopyOnWriteArrayList<List<com.letta.mobile.data.canvas.CanvasPresence>>()
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            presenceTransport.observePresence(canvasId).collect { seen += it }
        }
        var open by mutableStateOf(true)
        setContent {
            if (open) {
                CanvasWorkspace(
                    session = session,
                    presenceTransport = presenceTransport,
                    currentPeerId = "desktop-peer",
                    currentPeerName = "Ada",
                )
            }
        }
        onNodeWithContentDescription("Canvas workspace").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5000) {
            seen.lastOrNull()?.singleOrNull()?.let { it.peerId == "desktop-peer" && it.isActive } == true
        }
        val local = seen.last().single()
        kotlin.test.assertEquals("Ada", local.displayName)
        // The board draws peers' cursors only: its own never appears as a labelled cursor.
        onAllNodesWithText("Ada").assertCountEquals(0)

        open = false
        waitUntil(timeoutMillis = 5000) { seen.lastOrNull()?.none { it.isActive } == true }
        watcher.cancel()
    }
}
