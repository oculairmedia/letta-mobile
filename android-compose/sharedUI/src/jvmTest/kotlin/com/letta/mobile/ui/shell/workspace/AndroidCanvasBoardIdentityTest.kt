@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.ProvideCanvasHostWithBoards
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The Android host provider must join separately mounted pages and board modules. */
class AndroidCanvasBoardIdentityTest {
    @Test
    fun androidProviderAdoptsThePageAndKeepsEitherSurvivorAlive() = runDesktopComposeUiTest {
        val store = InMemoryCanvasDocumentStore()
        val page = runBlocking { CanvasSession.create(store, CanvasCreateOptions(title = "Original board")) }
        val boards = CanvasBoardSessions(store)
        var registry: CanvasSessionRegistry? = null
        var showPage by mutableStateOf(true)
        var showModule by mutableStateOf(false)
        setContent {
            ProvideCanvasHostWithBoards(null, boards) {
                val live = LocalCanvasSessionRegistry.current
                SideEffect { registry = live }
                Row {
                    if (showPage) CanvasWorkspace(session = page, modifier = Modifier.size(BoardSize), onNavigateBack = null)
                    if (showModule) ShellCanvasModule(page.canvasId, Modifier.size(BoardSize))
                }
            }
        }
        waitForIdle()
        val adopted = runBlocking { boards.acquire(page.canvasId) }
        assertSame(page, adopted, "the Android table adopts the mounted page, not a second session")
        boards.release(page)
        showModule = true
        waitForIdle()
        runBlocking { page.updateTitle("Shared edit") }
        waitUntil(timeoutMillis = TimeoutMillis) { onAllNodes(hasText("Shared edit", substring = true)).fetchSemanticsNodes().size >= 2 }
        showPage = false
        waitForIdle()
        assertSame(page, registry?.get(page.canvasId), "the module survives page removal")
        runBlocking { page.updateTitle("Surviving module") }
        waitUntil(timeoutMillis = TimeoutMillis) {
            onAllNodes(hasText("Surviving module", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        showPage = true
        waitForIdle()
        showModule = false
        waitForIdle()
        assertSame(page, registry?.get(page.canvasId), "the page survives module removal")
        showPage = false
        waitUntil(timeoutMillis = TimeoutMillis) { registry?.get(page.canvasId) == null }
        assertEquals(0, boards.holds(page.canvasId))
    }
}

private const val TimeoutMillis = 10_000L
private val BoardSize = 500.dp
