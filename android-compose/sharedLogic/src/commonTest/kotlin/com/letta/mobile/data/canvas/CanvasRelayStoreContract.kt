package com.letta.mobile.data.canvas

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What every [CanvasRelayStore] must do (I10). The in-memory store runs it here; the durable host
 * store runs the same suite on the JVM, so either can stand behind the relay.
 */
abstract class CanvasRelayStoreContract {
    /** A fresh, empty store. */
    abstract suspend fun newStore(): CanvasRelayStore

    /** The same store as [store] after a restart, when it is durable; null when it is not. */
    open suspend fun reopen(store: CanvasRelayStore): CanvasRelayStore? = null

    private fun op(id: String, lamport: Long = 1L) =
        CanvasOp.SetBackgroundOp(opId = id, actorId = "user", lamport = lamport, colorHex = "#000000")

    @Test
    fun theFirstBindingWinsAndIsKept() = runTest {
        val store = newStore()
        assertEquals(CanvasId("a"), store.bind("conversation:1", CanvasId("a")))
        assertEquals(CanvasId("a"), store.bind("conversation:1", CanvasId("b")))
        assertEquals(CanvasId("b"), store.bind("conversation:2", CanvasId("b")))
    }

    @Test
    fun concurrentBindsAgreeOnOneCanvas() = runTest {
        val store = newStore()
        val bound = (1..16).map { i -> async { store.bind("conversation:race", CanvasId("proposal-$i")) } }.awaitAll()
        assertEquals(1, bound.toSet().size, "every caller must get the same canvas: $bound")
    }

    @Test
    fun appendsCountFromOneAndDuplicatesKeepTheirCursor() = runTest {
        val store = newStore()
        assertEquals(CanvasRelayAppend(1, duplicate = false), store.append("t", op("x"), "peer-a"))
        assertEquals(CanvasRelayAppend(2, duplicate = false), store.append("t", op("y"), "peer-b"))
        assertEquals(CanvasRelayAppend(1, duplicate = true), store.append("t", op("x"), "peer-b"))
        assertEquals(2L, store.head("t"))
        assertEquals(listOf("x" to "peer-a", "y" to "peer-b"), store.readAfter("t", 0).map { it.op.opId to it.origin })
    }

    @Test
    fun readAfterPagesInCursorOrder() = runTest {
        val store = newStore()
        (1..10).forEach { store.append("t", op("op-$it", it.toLong()), "peer") }
        assertEquals(listOf(4L, 5L, 6L), store.readAfter("t", afterCursor = 3, limit = 3).map { it.cursor })
        assertTrue(store.readAfter("t", afterCursor = 10).isEmpty())
        assertEquals(0L, store.head("empty"))
    }

    @Test
    fun topicsAreIsolated() = runTest {
        val store = newStore()
        store.append("conversation:a", op("same-id"), "peer")
        val intoB = store.append("conversation:b", op("same-id"), "peer")
        assertFalse(intoB.duplicate, "an op id is unique per topic, not across topics")
        assertEquals(1L, store.head("conversation:a"))
        assertEquals(1L, store.head("conversation:b"))
    }

    @Test
    fun aDurableStoreKeepsEverythingAcrossARestart() = runTest {
        val store = newStore()
        store.bind("conversation:1", CanvasId("canvas-1"))
        store.append("conversation:1", op("x"), "peer-a")
        store.append("conversation:1", op("y"), "peer-b")
        val reopened = reopen(store) ?: return@runTest
        assertEquals(CanvasId("canvas-1"), reopened.bind("conversation:1", CanvasId("other")))
        assertEquals(2L, reopened.head("conversation:1"))
        assertEquals(CanvasRelayAppend(2, duplicate = true), reopened.append("conversation:1", op("y"), "peer-a"))
        assertEquals(CanvasRelayAppend(3, duplicate = false), reopened.append("conversation:1", op("z"), "peer-a"))
        assertEquals(listOf("x", "y", "z"), reopened.readAfter("conversation:1", 0).map { it.op.opId })
    }
}

class CanvasRelayStoreContractTest : CanvasRelayStoreContract() {
    override suspend fun newStore(): CanvasRelayStore = InMemoryCanvasRelayStore()
}
