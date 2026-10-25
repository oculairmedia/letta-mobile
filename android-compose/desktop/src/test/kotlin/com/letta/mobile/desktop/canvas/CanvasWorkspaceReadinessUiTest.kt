@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasWorkspaceReadinessUiTest {
    @Test
    fun awaitCanvasReadyWaitsForTheDelayedSessionAndInteractiveBoard() = runComposeUiTest {
        val releaseRead = CompletableDeferred<Unit>()
        val backingStore = InMemoryCanvasDocumentStore()
        val canvasId = CanvasId("delayed-canvas")
        runBlocking {
            backingStore.upsert(CanvasDocument(id = canvasId, title = "Delayed board"))
        }
        val store = FrameGatedCanvasDocumentStore(backingStore, releaseRead)
        val session = CanvasSession(canvasId, store)

        mainClock.autoAdvance = false
        setContent {
            ReleaseCanvasReadAfterFrames(releaseRead)
            CanvasWorkspace(session = session)
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()

        assertTrue(store.readStarted.isCompleted, "session loading reached the gated store read")
        assertFalse(releaseRead.isCompleted, "the test clock still holds session loading")
        assertTrue(
            onAllNodes(hasContentDescription("Canvas board")).fetchSemanticsNodes().isEmpty(),
            "the interactive board is absent while its session is loading",
        )

        mainClock.autoAdvance = true
        awaitCanvasReady()

        assertTrue(releaseRead.isCompleted)
        assertNotNull(session.document.value)
        onNodeWithContentDescription("Canvas board").assertExists()
    }
}

@Composable
private fun ReleaseCanvasReadAfterFrames(releaseRead: CompletableDeferred<Unit>) {
    LaunchedEffect(releaseRead) {
        repeat(3) { withFrameNanos { } }
        releaseRead.complete(Unit)
    }
}

private class FrameGatedCanvasDocumentStore(
    private val delegate: InMemoryCanvasDocumentStore,
    private val releaseRead: CompletableDeferred<Unit>,
) : CanvasDocumentStore by delegate {
    val readStarted = CompletableDeferred<Unit>()

    override suspend fun get(id: CanvasId): CanvasDocument? {
        readStarted.complete(Unit)
        releaseRead.await()
        return delegate.get(id)
    }
}
