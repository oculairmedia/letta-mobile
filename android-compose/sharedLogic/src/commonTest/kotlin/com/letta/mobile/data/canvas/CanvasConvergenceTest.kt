package com.letta.mobile.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Convergence is the property the op log exists for: two peers handed the same ops must reach the
 * same scene whatever order those ops arrive in.
 *
 * `CanvasMultiplayerSyncTest` applies each op and relays it fully before the next, so it never
 * produces a divergent arrival order — which is why it passed while remove-then-update resurrected
 * elements and concurrent background changes settled differently on each peer. Every test here
 * plays the *same* op set in both orders and compares, so a regression in the merge shows up as a
 * failure rather than as two users quietly looking at different canvases.
 */
class CanvasConvergenceTest {

    private val base =
        """{"bgColor":"#ffffffff","elements":[{"id":"X","type":"rect","_lamport":1,"_actorId":"seed"}]}"""

    private fun bothOrders(first: CanvasOp, second: CanvasOp, from: String = base): Pair<String, String> =
        CanvasOpProjector.project(from, listOf(first, second)) to
            CanvasOpProjector.project(from, listOf(second, first))

    @Test
    fun concurrentUpdateAndRemoveConverge() {
        val update = CanvasOp.UpdateElementOp(
            opId = "op-update", actorId = "peerA", lamport = 4,
            elementId = "X", elementJson = """{"id":"X","type":"rect","strokeColor":"#ff0000"}""",
        )
        val remove = CanvasOp.RemoveElementOp(opId = "op-remove", actorId = "peerB", lamport = 5, elementId = "X")

        val (ab, ba) = bothOrders(update, remove)

        assertEquals(ab, ba, "peers diverged on update vs remove")
        // The remove is the later write, so the element is gone on both sides.
        assertTrue("X" !in CanvasOpProjector.stripMetadataForDrawBox(ab), "removed element came back: $ab")
    }

    @Test
    fun anUpdateNewerThanTheRemoveSurvivesOnBothPeers() {
        val remove = CanvasOp.RemoveElementOp(opId = "op-remove", actorId = "peerA", lamport = 4, elementId = "X")
        val update = CanvasOp.UpdateElementOp(
            opId = "op-update", actorId = "peerB", lamport = 9,
            elementId = "X", elementJson = """{"id":"X","type":"rect","strokeColor":"#00ff00"}""",
        )

        val (ab, ba) = bothOrders(remove, update)

        assertEquals(ab, ba, "peers diverged when the update was the later write")
        assertTrue("#00ff00" in ab, "the winning update was lost: $ab")
    }

    @Test
    fun anAddArrivingAfterItsRemovalDoesNotResurrect() {
        // The peer that has not seen the add yet must still refuse it once the remove is known,
        // or it keeps an element the other peer has already dropped.
        val add = CanvasOp.AddElementOp(
            opId = "op-add", actorId = "peerA", lamport = 2,
            elementId = "Y", elementJson = """{"id":"Y","type":"ellipse"}""",
        )
        val remove = CanvasOp.RemoveElementOp(opId = "op-remove", actorId = "peerB", lamport = 6, elementId = "Y")

        val (ab, ba) = bothOrders(add, remove)

        assertEquals(ab, ba, "peers diverged on add vs remove")
        assertTrue("\"Y\"" !in CanvasOpProjector.stripMetadataForDrawBox(ab), "removed element came back: $ab")
    }

    @Test
    fun concurrentBackgroundChangesConverge() {
        val red = CanvasOp.SetBackgroundOp("op-red", "peerA", 7, "#ff0000ff")
        val blue = CanvasOp.SetBackgroundOp("op-blue", "peerB", 8, "#0000ffff")

        val (ab, ba) = bothOrders(red, blue)

        assertEquals(ab, ba, "peers diverged on background")
        assertTrue("#0000ffff" in ab, "the later background did not win: $ab")
    }

    @Test
    fun backgroundChangesAtTheSameLamportBreakTheTieTheSameWayOnEveryPeer() {
        val fromA = CanvasOp.SetBackgroundOp("op-a", "peerA", 7, "#ff0000ff")
        val fromB = CanvasOp.SetBackgroundOp("op-b", "peerB", 7, "#0000ffff")

        val (ab, ba) = bothOrders(fromA, fromB)

        assertEquals(ab, ba, "a lamport tie must resolve by actor, not by arrival")
    }

    @Test
    fun anElementOpOlderThanAReplaceCannotEditWhatTheReplaceWrote() {
        // A replace is authoritative, so its elements carry its own lamport; an older op that
        // arrives afterwards must lose against them rather than find unstamped content to overwrite.
        val replace = CanvasOp.ReplaceSceneOp(
            opId = "op-replace", actorId = "agent", lamport = 20,
            sceneJson = """{"bgColor":"#101010ff","elements":[{"id":"X","type":"rect","strokeColor":"#ffffff"}]}""",
        )
        val staleUpdate = CanvasOp.UpdateElementOp(
            opId = "op-stale", actorId = "peerA", lamport = 3,
            elementId = "X", elementJson = """{"id":"X","type":"rect","strokeColor":"#123456"}""",
        )

        val (ab, ba) = bothOrders(replace, staleUpdate)

        assertEquals(ab, ba, "peers diverged on replace vs a stale element op")
        assertTrue("#123456" !in ab, "a stale op overwrote what the replace wrote: $ab")
    }

    @Test
    fun aLongRunOfOpsConvergesWhateverOrderItArrivesIn() {
        val ops = listOf<CanvasOp>(
            CanvasOp.AddElementOp("o1", "peerA", 2, "a", """{"id":"a","type":"rect"}"""),
            CanvasOp.AddElementOp("o2", "peerB", 3, "b", """{"id":"b","type":"ellipse"}"""),
            CanvasOp.UpdateElementOp("o3", "peerA", 5, "a", """{"id":"a","type":"rect","strokeColor":"#111111"}"""),
            CanvasOp.RemoveElementOp("o4", "peerB", 6, "b"),
            CanvasOp.SetBackgroundOp("o5", "peerA", 7, "#222222ff"),
            CanvasOp.UpdateElementOp("o6", "peerB", 4, "b", """{"id":"b","type":"ellipse","strokeColor":"#333333"}"""),
            CanvasOp.SetBackgroundOp("o7", "peerB", 6, "#444444ff"),
        )

        val forward = CanvasOpProjector.project(base, ops)
        val reversed = CanvasOpProjector.project(base, ops.reversed())
        val shuffled = CanvasOpProjector.project(base, listOf(ops[4], ops[0], ops[6], ops[3], ops[5], ops[1], ops[2]))

        assertEquals(forward, reversed, "peers diverged between forward and reverse delivery")
        assertEquals(forward, shuffled, "peers diverged on a shuffled delivery")
    }

    @Test
    fun bookkeepingNeverReachesDrawBox() {
        val remove = CanvasOp.RemoveElementOp("op-remove", "peerA", 5, "X")
        val background = CanvasOp.SetBackgroundOp("op-bg", "peerA", 6, "#555555ff")

        val projected = CanvasOpProjector.project(base, listOf(remove, background))
        val forDrawBox = CanvasOpProjector.stripMetadataForDrawBox(projected)

        assertTrue("_removed" in projected, "the tombstone should be kept in the session scene")
        assertTrue("_removed" !in forDrawBox, "tombstones must not reach DrawBox: $forDrawBox")
        assertTrue("_bgLamport" !in forDrawBox, "background bookkeeping must not reach DrawBox: $forDrawBox")
        assertTrue("_lamport" !in forDrawBox, "element bookkeeping must not reach DrawBox: $forDrawBox")
    }
}
