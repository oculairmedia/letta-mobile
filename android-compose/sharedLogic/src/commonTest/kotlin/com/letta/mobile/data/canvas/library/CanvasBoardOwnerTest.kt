package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Owner replacement is shared policy; platform bindings only supply retained lifecycle callbacks. */
class CanvasBoardOwnerTest {
    @Test
    fun initialLoadingAndSameIdentityEditsKeepTheStartupOwner() {
        var creations = 0
        val owner = CanvasBoardOwner { creations++; CanvasBoardSessions(InMemoryCanvasDocumentStore()) }
        val startup = owner.boards.value
        owner.updateBackend(null)
        owner.updateBackend(backend())
        owner.updateBackend(backend().copy(accessToken = "edited-token", parkedServerUrl = "https://parked.invalid"))
        assertSame(startup, owner.boards.value)
        assertEquals(1, creations)
        assertEquals(0L, owner.context.value.generation)
        assertSame(startup, owner.context.value.boards)
        owner.close()
    }

    @Test
    fun replacementAndSignOutForgetOldHoldsAndRegistrations() = runTest {
        val owner = CanvasBoardOwner { CanvasBoardSessions(InMemoryCanvasDocumentStore()) }
        owner.updateBackend(backend())
        val old = owner.boards.value
        val page = old.acquirePage(null, null, null)
        val dispose = old.registry.registerMount(page)
        owner.updateBackend(backend().copy(id = Replacement))
        assertNotSame(old, owner.boards.value)
        assertEquals(1L, owner.context.value.generation)
        assertSame(owner.boards.value, owner.context.value.boards)
        assertEquals(0, old.holds(page.canvasId))
        assertNull(old.registry.get(page.canvasId))
        val active = owner.boards.value
        val other = active.acquirePage(null, null, null)
        val disposeOther = active.registry.registerMount(other)
        owner.updateBackend(null)
        assertNotSame(active, owner.boards.value)
        assertEquals(2L, owner.context.value.generation)
        assertSame(owner.boards.value, owner.context.value.boards)
        assertEquals(0, active.holds(other.canvasId))
        assertNull(active.registry.get(other.canvasId))
        dispose()
        disposeOther()
        owner.close()
    }

    private fun backend() = LettaConfig(Original, LettaConfig.Mode.SELF_HOSTED, "https://owner-policy.invalid")
}

private const val Original = "owner-original-backend"
private const val Replacement = "owner-replacement-backend"
