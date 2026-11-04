package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class CanvasSessionRegistryTest {
    @Test
    fun repeatedDisposalOfOneMountKeepsAnotherMountOfSameSession() {
        val registry = CanvasSessionRegistry()
        val session = CanvasSession(CanvasId("shared"), InMemoryCanvasDocumentStore())
        val firstDispose = registry.registerMount(session)
        val secondDispose = registry.registerMount(session)

        firstDispose()
        firstDispose()
        assertSame(session, registry.get(session.canvasId))
        secondDispose()
        secondDispose()
        assertNull(registry.get(session.canvasId))
    }

    @Test
    fun staleDisposerCannotRemoveNewRegistrationAfterClear() {
        val registry = CanvasSessionRegistry()
        val session = CanvasSession(CanvasId("stable"), InMemoryCanvasDocumentStore())
        val oldDispose = registry.registerMount(session)
        registry.clear()
        val newDispose = registry.registerMount(session)
        oldDispose()
        oldDispose()
        assertSame(session, registry.get(session.canvasId))
        newDispose()
        newDispose()
        assertNull(registry.get(session.canvasId))
    }

    @Test
    fun registriesAndCanvasIdsRemainIndependent() {
        val first = CanvasSessionRegistry()
        val second = CanvasSessionRegistry()
        val store = InMemoryCanvasDocumentStore()
        val sessionA = CanvasSession(CanvasId("a"), store)
        val sessionB = CanvasSession(CanvasId("b"), store)
        val isolated = CanvasSession(CanvasId("a"), store)
        val disposeA = first.registerMount(sessionA)
        val disposeB = first.registerMount(sessionB)
        val disposeIsolated = second.registerMount(isolated)
        disposeA()
        assertNull(first.get(sessionA.canvasId))
        assertSame(sessionB, first.get(sessionB.canvasId))
        assertSame(isolated, second.get(sessionA.canvasId))
        disposeB()
        disposeIsolated()
        assertNull(first.get(sessionB.canvasId))
        assertNull(second.get(sessionA.canvasId))
    }
}
