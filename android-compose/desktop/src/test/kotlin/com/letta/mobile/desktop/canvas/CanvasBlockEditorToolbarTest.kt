@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.canvas.BlockButtons
import com.letta.mobile.ui.canvas.CanvasBlockEditor
import com.letta.mobile.ui.canvas.NoteToolbar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hoisted toolbar converts the caret's block and the change reaches the session at once. */
class CanvasBlockEditorToolbarTest {
    @Test
    fun blockTypeFromTheHoistedBarConvertsAndPersistsImmediately() = runComposeUiTest {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val session = kotlinx.coroutines.runBlocking {
            com.letta.mobile.data.canvas.CanvasSession.create(store, com.letta.mobile.data.canvas.CanvasCreateOptions(title = "p"))
        }
        kotlinx.coroutines.runBlocking { session.setDocument("n", "") }
        var toolbar: NoteToolbar? = null
        setContent {
            CanvasBlockEditor(session = session, documentId = "n", storedJson = "", active = true, onToolbar = { toolbar = it })
        }
        waitForIdle()
        assertEquals(listOf("paragraph"), toolbar!!.holder.state.blocks.map { it.type.typeId }, "a fresh note has one paragraph to type into")

        runOnUiThread { toolbar!!.apply(BlockButtons.first { it.label == "To-do" }) }
        waitForIdle()
        assertEquals(listOf("todo"), toolbar!!.holder.state.blocks.map { it.type.typeId })
        waitUntil(timeoutMillis = 3000) { session.documents().single().json.contains("\"todo\"") }

        runOnUiThread { toolbar!!.apply(BlockButtons.first { it.label == "Divider" }) }
        waitForIdle()
        // The editor follows a divider with a paragraph so the caret has somewhere to land.
        assertEquals(listOf("todo", "divider", "paragraph"), toolbar!!.holder.state.blocks.map { it.type.typeId })
        waitUntil(timeoutMillis = 3000) { session.documents().single().json.contains("\"divider\"") }
        assertTrue(session.documents().single().json.contains("\"todo\""))
    }
}
