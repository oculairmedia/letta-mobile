package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NotebookNoteEditorTest {
    @Test
    fun editsSaveAndDiscardThroughTheSharedSessionAtCompactWidth() = runComposeUiTest {
        val backend = EditorTestBackend()
        val session = NotebookNoteSession(backend.source.id, backend)
        setContent { MaterialTheme { NotebookNoteEditor(session, Modifier.size(360.dp, 640.dp)) } }
        onNodeWithTag("notebook-note-markdown").assertTextContains("original")
        onNodeWithTag("notebook-note-markdown").performTextReplacement("# edited")
        onNodeWithText("Unsaved changes").assertIsDisplayed()
        onNodeWithText("Save", substring = false).performClick()
        onNodeWithText("No unsaved changes").assertExists()
        runOnIdle { assertEquals("# edited", backend.source.markdown) }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("discard me")
        onNodeWithText("Discard draft and reload").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("# edited")
    }

    @Test
    fun failedPersistenceRemainsVisibleAndRetryOnlyFlushes() = runComposeUiTest {
        val backend = EditorTestBackend().apply { persist = false }
        val session = NotebookNoteSession(backend.source.id, backend)
        setContent { MaterialTheme { NotebookNoteEditor(session, Modifier.size(900.dp, 640.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("draft")
        onNodeWithText("Save", substring = false).performClick()
        onNodeWithText("Applied locally; persistence is still pending").assertIsDisplayed()
        onNodeWithText("disk failed").assertExists()
        onNodeWithText("Save", substring = false).assertIsNotEnabled()
        runOnIdle { backend.persist = true }
        onNodeWithText("Retry persistence").performClick()
        onNodeWithText("No unsaved changes").assertExists()
        runOnIdle {
            assertEquals(1, backend.saves)
            assertEquals(1, backend.flushes)
            assertFalse(session.state.value.dirty)
        }
    }

    @Test
    fun refreshShowsConflictWithoutDiscardingDraftAndRequiresExplicitReload() = runComposeUiTest {
        val backend = EditorTestBackend()
        val session = NotebookNoteSession(backend.source.id, backend)
        setContent { MaterialTheme { NotebookNoteEditor(session, Modifier.size(600.dp, 640.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("my draft")
        runOnIdle { backend.source = backend.source.copy(markdown = "peer") }
        onNodeWithText("Refresh", substring = false).performClick()
        onNodeWithText("The note changed elsewhere.", substring = true).assertExists()
        onNodeWithTag("notebook-note-markdown").assertTextContains("my draft")
        onNodeWithText("Save", substring = false).assertIsNotEnabled()
        onNodeWithText("Discard draft and reload").performClick()
        onNodeWithTag("notebook-note-markdown").assertTextContains("peer")
        onNodeWithText("The note changed elsewhere.", substring = true).assertDoesNotExist()
    }

    @Test
    fun replacingSessionCancelsOldUiSaveAndCannotChangeNewDocument() = runComposeUiTest {
        val firstBackend = EditorTestBackend()
        val a = NotebookNoteSession(firstBackend.source.id, firstBackend)
        val secondBackend = EditorTestBackend().apply {
            source = source.copy(id = NotebookDocumentId("ffeeddccbbaa99887766554433221100"), markdown = "B")
        }
        val b = NotebookNoteSession(secondBackend.source.id, secondBackend)
        val selected = mutableStateOf(a)
        setContent { MaterialTheme { NotebookNoteEditor(selected.value, Modifier.size(600.dp, 640.dp)) } }
        onNodeWithTag("notebook-note-markdown").performTextReplacement("A draft")
        runOnIdle { firstBackend.saveRelease = CompletableDeferred() }
        onNodeWithText("Save", substring = false).performClick()
        onNodeWithText("Saving…").assertExists()
        runOnIdle {
            assertTrue(firstBackend.saveEntered.isCompleted)
            selected.value = b
        }
        onNodeWithTag("notebook-note-markdown").assertTextContains("B")
        runOnIdle {
            assertFalse(a.state.value.saving)
            assertEquals("A draft", a.state.value.draft)
            assertTrue(a.state.value.dirty)
            firstBackend.saveRelease?.complete(Unit)
        }
        onNodeWithTag("notebook-note-markdown").assertTextContains("B")
        runOnIdle { assertFalse(b.state.value.dirty) }
    }
}

private class EditorTestBackend : NotebookNoteBackend {
    var source = NotebookNoteSnapshot(NotebookDocumentId("00112233445566778899aabbccddeeff"), "Test note", "original")
    var persist = true
    var saves = 0
    var flushes = 0
    val saveEntered = CompletableDeferred<Unit>()
    var saveRelease: CompletableDeferred<Unit>? = null

    override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad {
        check(id == source.id)
        return NotebookNoteLoad.Found(source)
    }

    override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
        check(id == source.id)
        saves++
        saveEntered.complete(Unit)
        saveRelease?.await()
        if (source.markdown != expected) return NotebookNoteSave.Conflict(source)
        source = source.copy(markdown = replacement)
        return if (persist) NotebookNoteSave.Saved(source) else NotebookNoteSave.PendingDurability(source, "disk failed")
    }

    override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave {
        flushes++
        return if (persist) NotebookNoteSave.Saved(applied) else NotebookNoteSave.PendingDurability(applied, "disk failed")
    }
}
