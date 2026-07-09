package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * eaczz.1 (S1-T): pure unit tests for [ConnectionRegistry] — no QUIC, no
 * controller. Covers register/unregister/viewersFor, a connection viewing
 * multiple conversations, unregisterAll on disconnect, and concurrent
 * register/unregister safety.
 */
class ConnectionRegistryTest {

    private class FakeViewer(override val connectionId: String) : ViewerHandle {
        override suspend fun writeFrame(frame: String): Boolean = true
    }

    @Test
    fun registerAndViewersFor() = runTest {
        val reg = ConnectionRegistry()
        val a = FakeViewer("conn-a")
        val b = FakeViewer("conn-b")
        reg.register("conv-1", a)
        reg.register("conv-1", b)

        val viewers = reg.viewersFor("conv-1")
        assertEquals(setOf(a, b), viewers)
        assertTrue(reg.viewersFor("conv-2").isEmpty())
    }

    @Test
    fun unregisterRemovesOnlyThatViewer() = runTest {
        val reg = ConnectionRegistry()
        val a = FakeViewer("conn-a")
        val b = FakeViewer("conn-b")
        reg.register("conv-1", a)
        reg.register("conv-1", b)

        reg.unregister("conv-1", a)
        assertEquals(setOf(b), reg.viewersFor("conv-1"))

        reg.unregister("conv-1", b)
        assertTrue(reg.viewersFor("conv-1").isEmpty())
        assertEquals(0, reg.conversationCount())
    }

    @Test
    fun connectionCanViewMultipleConversations() = runTest {
        val reg = ConnectionRegistry()
        val a = FakeViewer("conn-a")
        reg.register("conv-1", a)
        reg.register("conv-2", a)

        assertEquals(setOf(a), reg.viewersFor("conv-1"))
        assertEquals(setOf(a), reg.viewersFor("conv-2"))
        assertEquals(2, reg.conversationCount())
    }

    @Test
    fun unregisterAllRemovesEveryEntryForConnection() = runTest {
        val reg = ConnectionRegistry()
        val a = FakeViewer("conn-a")
        val b = FakeViewer("conn-b")
        // conn-a views two conversations; conn-b shares one of them.
        reg.register("conv-1", a)
        reg.register("conv-2", a)
        reg.register("conv-1", b)

        reg.unregisterAll("conn-a")

        // conn-a gone from both; conn-b still present in conv-1.
        assertEquals(setOf(b), reg.viewersFor("conv-1"))
        assertTrue(reg.viewersFor("conv-2").isEmpty())
        assertFalse(reg.viewersFor("conv-1").any { it.connectionId == "conn-a" })
    }

    @Test
    fun concurrentRegisterUnregisterIsSafe() = runTest {
        val reg = ConnectionRegistry()
        // Hammer register/unregister across many coroutines; must not throw
        // ConcurrentModificationException and must end consistent.
        val jobs = (0 until 200).map { i ->
            async {
                val v = FakeViewer("conn-$i")
                reg.register("conv-shared", v)
                reg.unregister("conv-shared", v)
            }
        }
        jobs.awaitAll()
        assertTrue(reg.viewersFor("conv-shared").isEmpty())
        assertEquals(0, reg.conversationCount())
    }
}
