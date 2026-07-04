package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrohNodeConnectionSupportTest {

    // ---- ProcessScopedClientMessageDedupe (P3-4, 3wq5g) --------------------

    @Test
    fun dedupeMarksFirstSeenNewAndDuplicatesHandled() {
        val dedupe = ProcessScopedClientMessageDedupe()
        assertTrue(dedupe.markHandled("a"), "first sight is new")
        assertFalse(dedupe.markHandled("a"), "second sight is a duplicate")
        assertTrue(dedupe.markHandled("b"))
        assertTrue(dedupe.contains("a"))
        assertTrue(dedupe.contains("b"))
    }

    @Test
    fun dedupeTrimsOldestWhenOverCapacity() {
        val dedupe = ProcessScopedClientMessageDedupe(maxIds = 4, trimCount = 2)
        // Fill to capacity.
        listOf("1", "2", "3", "4").forEach { assertTrue(dedupe.markHandled(it)) }
        assertEquals(4, dedupe.size())
        // One more triggers a trim of the 2 oldest (1, 2), then adds 5.
        assertTrue(dedupe.markHandled("5"))
        assertEquals(3, dedupe.size())
        assertFalse(dedupe.contains("1"))
        assertFalse(dedupe.contains("2"))
        assertTrue(dedupe.contains("3"))
        assertTrue(dedupe.contains("5"))
        // A trimmed id can be re-added (the reconnect-window trade-off).
        assertTrue(dedupe.markHandled("1"))
    }

    // ---- ParkedTerminalStore (P3-2, q71yi) --------------------------------

    @Test
    fun parkedTerminalIsReplayedOnceThenRemoved() {
        val store = ParkedTerminalStore()
        store.park("cmid-1", """{"message_type":"error_message","status":"cancelled"}""")
        assertTrue(store.contains("cmid-1"))
        val taken = store.takeParked("cmid-1")
        assertEquals("""{"message_type":"error_message","status":"cancelled"}""", taken)
        assertFalse(store.contains("cmid-1"), "a parked terminal is consumed exactly once")
        assertNull(store.takeParked("cmid-1"))
    }

    @Test
    fun parkedTerminalStoreEvictsOldestOverCapacity() {
        val store = ParkedTerminalStore(maxEntries = 2)
        store.park("a", "1")
        store.park("b", "2")
        store.park("c", "3")
        assertEquals(2, store.size())
        assertNull(store.takeParked("a"), "oldest entry evicted over capacity")
        assertEquals("2", store.takeParked("b"))
        assertEquals("3", store.takeParked("c"))
    }

    // ---- ConnectionEventSeq / IrohEventSeqAllocator (P3-4 race) -----------

    @Test
    fun connectionEventSeqIsStrictlyMonotonic() {
        val seq = ConnectionEventSeq(base = 10L)
        assertEquals(10L, seq.next())
        assertEquals(11L, seq.next())
        assertEquals(12L, seq.next())
    }

    @Test
    fun differentConnectionsDrawDisjointRanges() {
        val a = IrohEventSeqAllocator.newConnectionSeq()
        val b = IrohEventSeqAllocator.newConnectionSeq()
        val aFirst = a.next()
        val bFirst = b.next()
        // Disjoint bases (>= one stride apart) — a connection's whole run of
        // seqs cannot collide with another's within one process.
        assertTrue(
            kotlin.math.abs(bFirst - aFirst) >= IrohEventSeqAllocator.STRIDE,
            "expected disjoint bases at least one stride apart, got a=$aFirst b=$bFirst",
        )
    }

    // ---- OpenToolCallTracker + DanglingToolCallSynthesizer (P3-5, 8s45p) --

    @Test
    fun trackerReportsOpenToolCallUntilItsReturnArrives() {
        val tracker = OpenToolCallTracker()
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-1","tool_name":"grep" """))
        assertEquals(listOf("tc-1"), tracker.openIds())
        // The matching tool_return closes it.
        tracker.observe(streamDelta(""" "message_type":"tool_return_message","tool_call_id":"tc-1","status":"success" """))
        assertTrue(tracker.isEmpty())
    }

    @Test
    fun trackerKeepsUnreturnedCallsOpen() {
        val tracker = OpenToolCallTracker()
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-1" """))
        tracker.observe(streamDelta(""" "message_type":"tool_call_message","tool_call_id":"tc-2" """))
        tracker.observe(streamDelta(""" "message_type":"tool_return_message","tool_call_id":"tc-1","status":"success" """))
        assertEquals(listOf("tc-2"), tracker.openIds(), "only the unreturned call stays open")
    }

    @Test
    fun cancelledToolReturnDeltaIsAnErrorCancelledReturn() {
        val delta = DanglingToolCallSynthesizer.cancelledToolReturnDelta("tc-9")
        assertEquals("tool_return_message", delta["message_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tc-9", delta["tool_call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("error", delta["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cancelled", delta["tool_return"]?.jsonPrimitive?.contentOrNull)
    }

    private fun streamDelta(deltaFields: String): String =
        """{"type":"stream_delta","event_seq":1,"delta":{${deltaFields.trim()}}}"""
}
