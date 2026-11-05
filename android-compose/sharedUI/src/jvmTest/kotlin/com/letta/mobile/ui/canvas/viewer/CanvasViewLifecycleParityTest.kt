@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasNotebookIdentity
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasDocumentViewerHost
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.notes.NOTEBOOK_NOTE_VIEWER_ID
import com.letta.mobile.ui.canvas.notes.notebookCanvasViewers
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewer
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate before views mount by default (letta-mobile-n2stx.2.3): cycling every view of a canvas
 * and closing it leaves no mount, subscription, view composition or retained note behind; a
 * replaced session's note follows the new canvas; and the selector and note fit a phone-width
 * and a desktop-width pane.
 */
class CanvasViewLifecycleParityTest {
    @Test
    fun cyclingEveryViewThenClosingReturnsToBaseline() = runComposeUiTest {
        val presence = CountingPresence()
        val session = session(CANVAS_A)
        val sessions = CanvasSessionRegistry()
        val probe = ProbeViewer()
        val viewers = notebookCanvasViewers(Notebooks, NoteStore()).also { it.register(probe.viewer) }
        val shown = mutableStateOf(true)
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewerRegistry(viewers) {
                    if (shown.value) {
                        CanvasWorkspace(session = session, sessionRegistry = sessions, presenceTransport = presence, showTitle = false)
                    }
                }
            }
        }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { sessions.get(CANVAS_A) === session }

        val note = NOTEBOOK_NOTE_VIEWER_ID
        val cycle = listOf(note, PROBE, CanvasDocumentViewerId.TEXT, note, CanvasDocumentViewerId.CANVAS)
        repeat(2) {
            for (view in cycle) {
                onNodeWithTag(canvasViewerOptionTag(view)).performScrollTo().performClick()
                waitForIdle()
            }
        }
        runOnIdle { assertEquals(0, probe.active.get()) }

        runOnIdle { shown.value = false }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { sessions.get(CANVAS_A) == null && presence.active.get() == 0 }
        assertNull(sessions.get(CANVAS_A))
        assertEquals(0, probe.active.get())
        assertTrue(runBlocking { viewers.prepareClose() })
    }

    @Test
    fun aReplacedSessionShowsTheNewCanvasNote() = runComposeUiTest {
        val viewers = notebookCanvasViewers(Notebooks, NoteStore())
        val current = mutableStateOf(session(CANVAS_A))
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewerRegistry(viewers) {
                    CanvasWorkspace(session = current.value, showTitle = false)
                }
            }
        }
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()
        onNodeWithTag(MARKDOWN).assertTextContains("note of A")

        runOnIdle { current.value = session(CANVAS_B) }
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()

        onNodeWithTag(MARKDOWN).assertTextContains("note of B")
    }

    @Test
    fun theSelectorAndTheNoteFitAPhoneWidthPane() = runComposeUiTest { assertFits(PHONE_WIDTH) }

    @Test
    fun theSelectorAndTheNoteFitADesktopWidthPane() = runComposeUiTest { assertFits(DESKTOP_WIDTH) }

    private fun ComposeUiTest.assertFits(width: Dp) {
        val session = session(CANVAS_A)
        val viewers = notebookCanvasViewers(Notebooks, NoteStore())
        setContent {
            MaterialTheme {
                ProvideCanvasDocumentViewerRegistry(viewers) {
                    Box(Modifier.width(width).height(PANE_HEIGHT).testTag(PANE)) {
                        CanvasDocumentViewerHost(session, Modifier.fillMaxSize()) { Text("Board", it) }
                    }
                }
            }
        }
        for (view in listOf(CanvasDocumentViewerId.CANVAS, CanvasDocumentViewerId.TEXT, NOTEBOOK_NOTE_VIEWER_ID)) {
            onNodeWithTag(canvasViewerOptionTag(view)).performScrollTo().assertIsDisplayed()
        }
        onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()

        onNodeWithTag(MARKDOWN).assertIsDisplayed()
        val pane = onNodeWithTag(PANE).getBoundsInRoot()
        val note = onNodeWithTag(MARKDOWN).getBoundsInRoot()
        assertTrue(note.left >= pane.left && note.right <= pane.right, "note $note overflows pane $pane at $width")
        assertTrue(note.right - note.left > 0.dp)
    }

    /** Each canvas's note: A and B are separate notebooks. */
    private object Notebooks : CanvasNotebookIdentity {
        override suspend fun notebookOf(canvasId: CanvasId): NotebookDocumentId? = when (canvasId) {
            CANVAS_A -> NOTE_A
            CANVAS_B -> NOTE_B
            else -> null
        }

        override suspend fun canvasOf(notebook: NotebookDocumentId): CanvasId? = when (notebook) {
            NOTE_A -> CANVAS_A
            NOTE_B -> CANVAS_B
            else -> null
        }
    }

    private class NoteStore : NotebookNoteBackend {
        private val notes = mutableMapOf(
            NOTE_A to NotebookNoteSnapshot(NOTE_A, "A", "note of A"),
            NOTE_B to NotebookNoteSnapshot(NOTE_B, "B", "note of B"),
        )

        override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad =
            notes[id]?.let(NotebookNoteLoad::Found) ?: NotebookNoteLoad.NotFound

        override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
            val current = notes.getValue(id)
            if (current.markdown != expected) return NotebookNoteSave.Conflict(current)
            return NotebookNoteSave.Saved(current.copy(markdown = replacement).also { notes[id] = it })
        }

        override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave = NotebookNoteSave.Saved(applied)
    }

    /** A plain view that counts its live compositions. */
    private class ProbeViewer {
        val active = AtomicInteger()
        val viewer = CanvasDocumentViewer(PROBE, label = { "Probe" }, render = { _, modifier ->
            DisposableEffect(Unit) {
                active.incrementAndGet()
                onDispose { active.decrementAndGet() }
            }
            Text("probe", modifier)
        })
    }

    private class CountingPresence : CanvasPresenceTransport {
        val active = AtomicInteger()
        override suspend fun updatePresence(canvasId: CanvasId, presence: CanvasPresence) = Unit
        override fun observePresence(canvasId: CanvasId): Flow<List<CanvasPresence>> = flow {
            active.incrementAndGet()
            try {
                emit(emptyList())
                awaitCancellation()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private object NoSync : CanvasSyncTransport {
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = emptyFlow()
    }

    private fun session(id: CanvasId): CanvasSession = runBlocking {
        CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(
                canvasId = id,
                agentId = "peer-1",
                initialSceneJson = """{"elements":[]}""",
                syncTransport = NoSync,
            ),
        )
    }

    private companion object {
        val CANVAS_A = CanvasId("canvas-gate-a")
        val CANVAS_B = CanvasId("canvas-gate-b")
        val NOTE_A = NotebookDocumentId("00112233445566778899aabbccddeeff")
        val NOTE_B = NotebookDocumentId("ffeeddccbbaa99887766554433221100")
        val PROBE = CanvasDocumentViewerId("test.probe")
        val PHONE_WIDTH = 360.dp
        val DESKTOP_WIDTH = 1200.dp
        val PANE_HEIGHT = 720.dp
        const val MARKDOWN = "notebook-note-markdown"
        const val PANE = "gate-pane"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
