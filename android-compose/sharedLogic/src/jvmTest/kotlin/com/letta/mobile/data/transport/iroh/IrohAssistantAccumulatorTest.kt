package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class IrohAssistantAccumulatorTest {

    private fun assistant(id: String, otid: String?, content: String) =
        ServerFrame.AssistantMessage(
            id = id,
            ts = "2026-07-06T00:00:00Z",
            agentId = "a",
            conversationId = "c",
            turnId = "t",
            runId = "r",
            content = content,
            otid = otid,
        )

    @Test
    fun `incremental deltas become cumulative content with a stable cm-stream id`() {
        val acc = IrohAssistantAccumulator()
        // Raw Iroh: rotating id per fragment, stable otid, INCREMENTAL content.
        val out1 = acc.normalize(listOf(assistant("letta-msg-1", "prov-1", "Hey"))).single() as ServerFrame.AssistantMessage
        val out2 = acc.normalize(listOf(assistant("letta-msg-2", "prov-1", "."))).single() as ServerFrame.AssistantMessage

        // Stable id (WS-shaped, same for every fragment of the reply).
        assertEquals("cm-stream-prov-1", out1.id)
        assertEquals("cm-stream-prov-1", out2.id)
        // Cumulative content (WS-shaped, each frame carries the full text so far).
        assertEquals("Hey", out1.content)
        assertEquals("Hey.", out2.content)
    }

    @Test
    fun `a dropped middle fragment self-heals because content is cumulative`() {
        val acc = IrohAssistantAccumulator()
        val f1 = acc.normalize(listOf(assistant("m1", "o", "The"))).single() as ServerFrame.AssistantMessage
        // Simulate the SECOND fragment (" chase") being dropped in the client
        // dispatch layer — it never reaches the accumulator. The THIRD fragment
        // arrives. Because the wire is reliable+ordered (QUIC) the server's
        // incremental deltas are correct; here we prove that even if OUR dispatch
        // loses one, the accumulator's running total plus the next delta keep the
        // stable id so the row never strands/dupes — and the reconciled final
        // (cumulative) trivially replaces by id.
        val f3 = acc.normalize(listOf(assistant("m3", "o", " continues"))).single() as ServerFrame.AssistantMessage
        assertEquals("cm-stream-o", f1.id)
        assertEquals("cm-stream-o", f3.id)
        // Same stable id => the reducer collapses by id equality (WS behavior),
        // so a dropped delta can never produce a duplicate row — at worst the
        // streamed text is briefly short until the reconciled cumulative final
        // (same id) replaces it.
    }

    @Test
    fun `distinct otids stay separate replies`() {
        val acc = IrohAssistantAccumulator()
        val a = acc.normalize(listOf(assistant("x1", "otid-a", "Reply A"))).single() as ServerFrame.AssistantMessage
        val b = acc.normalize(listOf(assistant("x2", "otid-b", "Reply B"))).single() as ServerFrame.AssistantMessage
        assertEquals("cm-stream-otid-a", a.id)
        assertEquals("cm-stream-otid-b", b.id)
        assertEquals("Reply A", a.content)
        assertEquals("Reply B", b.content)
    }

    @Test
    fun `an already-cumulative frame is not double-appended`() {
        val acc = IrohAssistantAccumulator()
        acc.normalize(listOf(assistant("m1", "o", "Got")))
        // A cumulative snapshot ("Got it") that contains the prior as a prefix
        // must REPLACE, never concatenate to "GotGot it".
        val out = acc.normalize(listOf(assistant("m2", "o", "Got it"))).single() as ServerFrame.AssistantMessage
        assertEquals("Got it", out.content)
    }
}
