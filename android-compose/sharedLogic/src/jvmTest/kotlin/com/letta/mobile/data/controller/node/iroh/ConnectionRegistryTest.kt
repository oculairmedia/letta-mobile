package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Deterministic endpoint-identity and connection-generation coverage for [ConnectionRegistry]. */
class ConnectionRegistryTest {

    private data class FakeViewer(override val connectionId: String) : ViewerHandle {
        override suspend fun writeFrame(frame: String): Boolean = true
    }

    @Test
    fun distinctCanonicalEndpointsNeverCollapseEvenWithSharedPrefix() = runTest {
        val registry = ConnectionRegistry()
        val first = FakeViewer("0123456789ab-endpoint-a")
        val second = FakeViewer("0123456789ab-endpoint-b")

        registry.register("conv-1", first)
        registry.register("conv-1", second)

        assertEquals(setOf(first, second), registry.viewersFor("conv-1"))
    }

    @Test
    fun reconnectAtomicallyReplacesTheSameEndpoint() = runTest {
        val registry = ConnectionRegistry()
        val stale = FakeViewer("endpoint-a")
        val live = FakeViewer("endpoint-a")

        val staleRegistration = registry.register("conv-1", stale)
        val liveRegistration = registry.register("conv-1", live)

        assertNotEquals(staleRegistration.generation, liveRegistration.generation)
        assertSame(live, registry.viewersFor("conv-1").single())
        registry.unregister("conv-1", stale)
        registry.release(staleRegistration)
        assertSame(
            live,
            registry.viewersFor("conv-1").single(),
            "stale per-conversation and disconnect cleanup must preserve the successor",
        )
    }

    @Test
    fun equalButDistinctStaleHandleCannotRemoveReplacement() = runTest {
        val registry = ConnectionRegistry()
        val stale = FakeViewer("endpoint-a")
        val replacement = stale.copy()
        assertEquals(stale, replacement, "test requires structural equality")

        registry.register("conv-1", stale)
        registry.register("conv-1", replacement)
        registry.unregister("conv-1", stale)

        assertSame(replacement, registry.viewersFor("conv-1").single())
    }

    @Test
    fun staleGenerationCannotRegisterOrReleaseAfterConcurrentReplacement() = runTest {
        val registry = ConnectionRegistry()
        val stale = FakeViewer("endpoint-a")
        val live = FakeViewer("endpoint-a")
        val staleRegistration = registry.claim(stale)
        val liveRegistration = registry.claim(live)

        val results = listOf(
            async { registry.register("conv-1", staleRegistration) },
            async { registry.register("conv-1", liveRegistration) },
        ).awaitAll()

        assertEquals(listOf(false, true), results)
        registry.release(staleRegistration)
        assertEquals(setOf<ViewerHandle>(live), registry.viewersFor("conv-1"))
    }

    @Test
    fun reconnectClaimReleasesPriorEndpointLifecycleAcrossConversations() = runTest {
        val registry = ConnectionRegistry()
        val stale = FakeViewer("endpoint-a")
        val staleRegistration = registry.register("conv-1", stale)
        registry.register("conv-2", stale)

        val live = FakeViewer("endpoint-a")
        val liveRegistration = registry.claim(live)

        assertTrue(registry.viewersFor("conv-1").isEmpty())
        assertTrue(registry.viewersFor("conv-2").isEmpty())
        assertFalse(registry.register("conv-stale", staleRegistration))
        assertTrue(registry.register("conv-2", liveRegistration))
        registry.release(staleRegistration)
        assertEquals(setOf<ViewerHandle>(live), registry.viewersFor("conv-2"))
    }

    @Test
    fun releasingCurrentGenerationRemovesOnlyItsSharedEndpointLifecycle() = runTest {
        val registry = ConnectionRegistry()
        val first = FakeViewer("endpoint-a")
        val second = FakeViewer("endpoint-b")
        val firstRegistration = registry.register("conv-1", first)
        registry.register("conv-2", first)
        registry.register("conv-1", second)

        registry.release(firstRegistration)

        assertEquals(setOf<ViewerHandle>(second), registry.viewersFor("conv-1"))
        assertTrue(registry.viewersFor("conv-2").isEmpty())
        assertEquals(1, registry.conversationCount())
    }

    @Test
    fun blankEndpointIdentityIsRejectedInsteadOfCollapsingUnknownViewers() = runTest {
        val registry = ConnectionRegistry()
        val result = runCatching { registry.claim(FakeViewer("")) }

        assertTrue(result.isFailure)
        assertEquals(0, registry.conversationCount())
    }
}
