package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.mount.client.CanvasMountLookup
import com.letta.mobile.data.canvas.mount.client.CanvasMountSource
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tests the retained component's final-clear callback, not a composition disposal callback. */
class AndroidCanvasBoardsTest {
    @Test
    fun finalWindowClearForgetsAllHoldsAndRegistrations() = runBlocking {
        // Hilt scopes have CLASS retention, so inspect the compiled annotation rather than reflection.
        val resource = AndroidCanvasBoards::class.java.getResourceAsStream("AndroidCanvasBoards.class")
        assertNotNull(resource)
        val bytecode = resource!!.use { it.readBytes().toString(Charsets.ISO_8859_1) }
        org.junit.Assert.assertTrue(bytecode.contains("L${ActivityRetainedScoped::class.java.name.replace('.', '/')};"))
        val lifecycle = mockk<ActivityRetainedLifecycle>(relaxed = true)
        val owner = AndroidCanvasBoards(Provider { CanvasBoardSessions(InMemoryCanvasDocumentStore()) }, lifecycle)
        val boards = owner.boards.value
        val page = boards.acquirePage(null, null, null)
        val dispose = boards.registry.registerMount(page)
        verify { lifecycle.addOnClearedListener(withArg { it.onCleared() }) }
        assertEquals(0, boards.holds(page.canvasId))
        assertEquals(null, boards.registry.get(page.canvasId))
        boards.release(page)
        dispose()
        assertEquals(0, boards.holds(page.canvasId))
    }

    @Test
    fun aNewBackendStartsWithNoMountsHeldOnThisDevice() = runBlocking {
        val owner = AndroidCanvasBoards(Provider { CanvasBoardSessions(InMemoryCanvasDocumentStore()) }, mockk(relaxed = true))
        val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
        val picked = CanvasId("canvas-b")
        owner.mounts(0L).mount(target, picked)

        assertSame(owner.mounts(0L), owner.mounts(0L))
        assertEquals(CanvasMountLookup.Found(picked, CanvasMountSource.Binding), owner.mounts(0L).resolve(target))
        assertEquals(CanvasMountLookup.None, owner.mounts(1L).resolve(target))
    }
}
