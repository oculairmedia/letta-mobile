package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.model.CanvasId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A host's [ShellCanvasPane] closing for a board mounted behind the chat, and the window's Chat options state. */
class ShellCanvasPaneTest {
    private val boardA = CanvasId("canvas-a")
    private val boardB = CanvasId("canvas-b")

    private class Pane : ShellCanvasPane {
        private val store = InMemoryCanvasDocumentStore()
        override var activeSession: CanvasSession? = null

        override fun open(id: CanvasId) {
            activeSession = CanvasSession(id, store)
        }

        override fun close() {
            activeSession = null
        }
    }

    @Test
    fun aMountClosesThePaneOnlyWhenItShowsThatBoard() {
        val pane = Pane()
        pane.open(boardA)

        pane.closeIfShowing(boardB)
        assertEquals(boardA, pane.activeSession?.canvasId)

        pane.closeIfShowing(boardA)
        assertNull(pane.activeSession)
    }

    @Test
    fun chatOptionsFollowTheWindowsFontScaleAndOpenChat() {
        val options = shellChatOptionsState(display = ChatDisplayOptions.Default, fontScale = 1f, autoPin = true, hasChat = false)

        assertTrue(options.autoPinNewChats)
        assertFalse(options.canDownloadTranscript)
    }
}
