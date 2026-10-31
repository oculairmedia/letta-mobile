@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasStorageFault
import com.letta.mobile.data.canvas.CanvasStorageHealth
import com.letta.mobile.data.canvas.CanvasStorageFaults
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerId
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real board and its delayed autosave, rather than editing the session directly. */
class CanvasWorkspaceDrawingHandoffTest {
    @Test
    fun drawingAndBackgroundSurviveAnAcknowledgedToggleBeforeAutosave() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val session = createSession(store)
        val controller = controller()
        mount(session, controller)
        drawRectangle()
        changeBackground()
        val draft = controller.state.value.elements
        val background = controller.state.value.bgColor
        assertTrue(draft.isNotEmpty(), "the pointer gesture must create an actual drawing element")
        assertEquals(0, store.writes.get(), "the 500ms autosave has not fired")

        assertTrue(mainClock.currentTime - editStarted < AUTOSAVE_MILLIS, "toggle is requested before the 500ms autosave")
        select(CanvasDocumentViewerId.TEXT)
        awaitText()
        onNodeWithContentDescription("Canvas board").assertDoesNotExist()
        val saved = DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson)
        assertEquals(draft, saved.elements)
        assertEquals(background, saved.bgColor)
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.sceneJsonOrEmpty())

        select(CanvasDocumentViewerId.CANVAS)
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithContentDescription("Canvas board").fetchSemanticsNodes().isNotEmpty() &&
                controller.state.value.elements == draft
        }
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        assertEquals(background, controller.state.value.bgColor)
        assertEquals(0, store.loads.get(), "returning to canvas must not reload an initialized session")
    }

    @Test
    fun failedSaveKeepsDraftVisibleUntilRetryIsAcknowledged() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val session = createSession(store)
        val controller = controller()
        mount(session, controller)
        drawRectangle()
        val draft = controller.state.value.elements
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        store.failWrites = true
        select(CanvasDocumentViewerId.TEXT)
        waitUntil { store.writes.get() > 0 }
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
        assertEquals(draft, controller.state.value.elements)

        gate.complete(Unit)
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithText("Error: Could not save drawing", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, store.failures.get())
        onNodeWithText("Error: Could not save drawing", substring = true).assertExists()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).assertIsSelected()
        assertEquals(draft, controller.state.value.elements)
        assertTrue(DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson).elements.isEmpty())

        store.failWrites = false
        store.writeGate = null
        select(CanvasDocumentViewerId.TEXT)
        awaitText()
        val persisted = DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson)
        assertEquals(draft, persisted.elements)
        select(CanvasDocumentViewerId.CANVAS)
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithContentDescription("Canvas board").fetchSemanticsNodes().isNotEmpty() &&
                controller.state.value.elements == draft
        }
        assertEquals(draft, controller.state.value.elements)
    }

    @Test
    fun reportedStorageFailureCannotAcknowledgeTheDraftHandoff() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val session = createSession(store)
        val controller = controller()
        mount(session, controller)
        changeBackground()
        val draft = controller.state.value.bgColor
        store.reportFaultOnly = true
        select(CanvasDocumentViewerId.TEXT)
        waitUntil { mainClock.advanceTimeByFrame(); store.storageFaults.value.isNotEmpty() }
        waitForIdle()
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).assertIsSelected()
        assertEquals(draft, controller.state.value.bgColor)
        assertTrue(DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson).bgColor != draft)

        val firstFailure = store.storageFaults.value.single()
        val beforeRetry = store.writes.get()
        select(CanvasDocumentViewerId.TEXT)
        waitUntil { mainClock.advanceTimeByFrame(); store.writes.get() > beforeRetry }
        waitForIdle()
        assertEquals(firstFailure, store.storageFaults.value.single(), "identical diagnostics may be conflated")
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        assertEquals(draft, controller.state.value.bgColor)

        store.reportFaultOnly = false
        val beforeSuccess = store.writes.get()
        select(CanvasDocumentViewerId.TEXT)
        awaitText()
        assertTrue(store.writes.get() > beforeSuccess, "acknowledgment requires an actual retry write")
        assertEquals(draft, DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson).bgColor)
        assertEquals(firstFailure, store.storageFaults.value.single(), "success keeps the historical diagnostic")
    }

    @Test
    fun readOnlyStorageKeepsTheDraftMountedWithoutAttemptingAWrite() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val session = createSession(store)
        val controller = controller()
        mount(session, controller)
        changeBackground()
        val draft = controller.state.value.bgColor
        store.faults.record(CanvasStorageFault(CanvasStorageFault.Kind.READ_ONLY, "read-only", session.canvasId, null, "read only", 1))
        select(CanvasDocumentViewerId.TEXT)
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithText("Error: Could not save drawing", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, store.writes.get())
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertDoesNotExist()
        assertEquals(draft, controller.state.value.bgColor)
    }

    @Test
    fun pendingDrawingHandoffPreservesAnUnseenRemoteElement() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val session = createSession(store)
        val controller = controller()
        mount(session, controller)
        drawRectangle()
        val localId = controller.state.value.elements.single().id
        runOnIdle {
            runBlocking {
                session.applyRemote(
                    CanvasOp.AddElementOp(
                        opId = "remote-add", actorId = "peer", lamport = 100,
                        elementId = "remote-rectangle",
                        elementJson = REMOTE_RECTANGLE,
                    ),
                )
            }
        }
        select(CanvasDocumentViewerId.TEXT)
        awaitText()
        val saved = DrawingSerializer.deserialize(runBlocking { store.saved(session.canvasId) }!!.sceneJson)
        assertEquals(setOf(localId, "remote-rectangle"), saved.elements.map { it.id }.toSet())
        select(CanvasDocumentViewerId.CANVAS)
        waitUntil { mainClock.advanceTimeByFrame(); controller.state.value.elements.size == 2 }
        assertEquals(setOf(localId, "remote-rectangle"), controller.state.value.elements.map { it.id }.toSet())
        val ops = runBlocking { session.opLog.getOps(session.canvasId, 0) }
        assertTrue(ops.none { it is CanvasOp.RemoveElementOp }, "unseen remote additions cannot become local removals")
    }

    @Test
    fun initialLoadSurvivesEarlyToggleAndDoesNotRunAgain() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val id = CanvasId("delayed-initial-load")
        runBlocking {
            store.upsert(CanvasDocument(id = id, title = "Delayed", revision = 7, sceneJson = INITIAL_SCENE, updatedAtEpochMs = 1))
        }
        store.resetCounts()
        val gate = CompletableDeferred<Unit>()
        store.loadGate = gate
        val session = CanvasSession(id, store)
        val registry = CanvasSessionRegistry()
        setContent { MaterialTheme { CanvasWorkspace(session = session, sessionRegistry = registry, showTitle = false) } }
        waitUntil { store.loads.get() == 1 }
        select(CanvasDocumentViewerId.TEXT)
        gate.complete(Unit)
        waitUntil { session.document.value != null }
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(INITIAL_SCENE)
        assertEquals(session, registry.get(id))
        val loaded = session.document.value!!
        runBlocking { session.updateTitle("Newer session") }
        select(CanvasDocumentViewerId.CANVAS)
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        select(CanvasDocumentViewerId.TEXT)
        onNodeWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).assertTextEquals(session.sceneJsonOrEmpty())
        assertTrue(session.document.value != loaded)
        assertEquals("Newer session", session.document.value!!.title)
        assertEquals(1, store.loads.get())
    }

    @Test
    fun cancelledLoadDoesNotUnregisterASessionItNeverRegistered() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val existing = createSession(store)
        val registry = CanvasSessionRegistry()
        registry.register(existing)
        store.loadGate = CompletableDeferred()
        val loading = CanvasSession(existing.canvasId, store)
        val visible = mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (visible.value) CanvasWorkspace(session = loading, sessionRegistry = registry, showTitle = false)
            }
        }
        waitUntil { store.loads.get() > 0 }
        runOnIdle { visible.value = false }
        waitForIdle()
        assertEquals(existing, registry.get(existing.canvasId))
    }

    @Test
    fun failedLoadReplacesAnEarlyTextViewerAndRetryLoadsTheSession() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val existing = createSession(store)
        val loading = CanvasSession(existing.canvasId, store)
        val registry = CanvasSessionRegistry()
        val gate = CompletableDeferred<Unit>()
        store.loadGate = gate
        store.failLoadsRemaining = 1
        setContent { MaterialTheme { CanvasWorkspace(session = loading, sessionRegistry = registry, showTitle = false) } }

        waitUntil { store.loads.get() == 1 }
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()
        onNodeWithText("Waiting for canvas content").assertIsDisplayed()
        gate.complete(Unit)
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithText("Canvas could not be loaded").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Canvas could not be loaded").assertIsDisplayed()
        onNodeWithText("Waiting for canvas content").assertDoesNotExist()
        onNodeWithText("Retry").assertIsDisplayed()
        assertEquals(null, registry.get(loading.canvasId), "failed sessions must not register")

        onNodeWithText("Retry").performClick()
        waitUntil {
            mainClock.advanceTimeByFrame()
            onAllNodesWithContentDescription("Canvas board").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        assertEquals(2, store.loads.get(), "retry must run a new load")
        assertEquals(loading, registry.get(loading.canvasId))
    }

    @Test
    fun cancellationDuringLoadIsNotRenderedAsAFailure() = runDesktopComposeUiTest(width = 900, height = 700) {
        val store = ControlledStore()
        val existing = createSession(store)
        val loading = CanvasSession(existing.canvasId, store)
        val registry = CanvasSessionRegistry()
        store.cancelLoads = true
        setContent { MaterialTheme { CanvasWorkspace(session = loading, sessionRegistry = registry, showTitle = false) } }

        waitUntil { store.loads.get() == 1 }
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).performClick()
        onNodeWithText("Waiting for canvas content").assertIsDisplayed()
        onNodeWithText("Canvas could not be loaded").assertDoesNotExist()
        assertEquals(null, registry.get(loading.canvasId), "a cancelled load must not register")
    }

    private var editStarted = 0L

    private suspend fun ComposeUiTest.mount(session: CanvasSession, controller: DrawBoxController) {
        setContent { MaterialTheme { CanvasWorkspace(session = session, controller = controller, showTitle = false) } }
        waitUntil { onAllNodesWithContentDescription("Canvas board").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Canvas board").assertIsDisplayed()
        waitUntil { onAllNodesWithText("Loaded from session", substring = true).fetchSemanticsNodes().isNotEmpty() }
        mainClock.autoAdvance = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        editStarted = mainClock.currentTime
    }

    private fun ComposeUiTest.drawRectangle() {
        onNodeWithContentDescription("Rectangle").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Canvas board").performTouchInput {
            swipe(Offset(300f, 280f), Offset(420f, 380f), durationMillis = 32)
        }
        editStarted = mainClock.currentTime
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.changeBackground() {
        onNodeWithContentDescription("More").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Background paper").performClick()
        editStarted = mainClock.currentTime
        mainClock.advanceTimeByFrame()
        // An outside click dismisses the background menu; choosing the already-selected canvas
        // chip leaves the viewer unchanged. Wait out the exit before the actual handoff click.
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.CANVAS)).performClick()
        mainClock.advanceTimeBy(150)
        onNodeWithContentDescription("Background paper").assertDoesNotExist()

    }

    private fun ComposeUiTest.select(id: CanvasDocumentViewerId) {
        onNodeWithTag(canvasViewerOptionTag(id)).performClick()
        mainClock.advanceTimeByFrame()
    }

    private suspend fun ComposeUiTest.awaitText() {
        waitUntil(timeoutMillis = 5_000) {
            mainClock.advanceTimeByFrame()
            onAllNodesWithTag(CANVAS_TEXT_VIEWER_CONTENT_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(canvasViewerOptionTag(CanvasDocumentViewerId.TEXT)).assertIsSelected()
    }

    private fun controller() = DrawBoxController(Reducer(UseCase()))

    private fun createSession(store: ControlledStore): CanvasSession = runBlocking {
        CanvasSession.create(store, CanvasCreateOptions(initialSceneJson = INITIAL_SCENE, agentId = "peer")).also { store.resetCounts() }
    }

    private class ControlledStore(
        private val backing: CanvasDocumentStore = InMemoryCanvasDocumentStore(),
    ) : CanvasDocumentStore by backing, CanvasStorageHealth {
        val faults = CanvasStorageFaults()
        override val storageFaults = faults.faults
        override fun saveFailureGeneration(canvasId: CanvasId): Long = faults.saveFailureGeneration(canvasId)
        override suspend fun awaitDurableSave(canvasId: CanvasId): Boolean = !reportFaultOnly
        val writes = AtomicInteger()
        val loads = AtomicInteger()
        val failures = AtomicInteger()
        @Volatile var writeGate: CompletableDeferred<Unit>? = null
        @Volatile var loadGate: CompletableDeferred<Unit>? = null
        @Volatile var failLoadsRemaining = 0
        @Volatile var cancelLoads = false
        @Volatile var failWrites = false
        @Volatile var reportFaultOnly = false

        override suspend fun get(id: CanvasId): CanvasDocument? {
            loads.incrementAndGet()
            loadGate?.await()
            if (cancelLoads) throw CancellationException("test load cancelled")
            if (failLoadsRemaining > 0) {
                failLoadsRemaining--
                error("test storage load failure")
            }
            return backing.get(id)
        }

        override suspend fun upsert(doc: CanvasDocument) {
            writes.incrementAndGet()
            writeGate?.await()
            if (failWrites) {
                failures.incrementAndGet()
                error("test storage failure")
            }
            if (reportFaultOnly) {
                faults.record(CanvasStorageFault(CanvasStorageFault.Kind.SAVE_FAILED, null, doc.id, null, "test reported failure", 1))
                return
            }
            backing.upsert(doc)
        }

        suspend fun saved(id: CanvasId): CanvasDocument? = backing.get(id)

        fun resetCounts() {
            writes.set(0)
            loads.set(0)
        }
    }

    private companion object {
        const val AUTOSAVE_MILLIS = 500
        const val INITIAL_SCENE = """{"bgColor":"#ffffffff","elements":[]}"""
        const val REMOTE_RECTANGLE = """{"id":"remote-rectangle","type":"Shape","zIndex":2,
            "points":["20.0,20.0","80.0,80.0"],"strokeColor":"#000000ff","strokeWidth":4.0,
            "shapeType":"RECTANGLE","modifiedAt":1}"""
    }
}
