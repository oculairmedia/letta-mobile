package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeLinkClosure
import kotlin.test.Test
import kotlin.test.assertEquals

/** The binding's close text maps to the closure the other end chose, unchanged; anything else is a loss. */
class IrohCloseReasonsTest {
    @Test
    fun anApplicationCloseByThePeerKeepsItsCodeAndReason() {
        assertEquals(NodeLinkClosure.NORMAL, IrohCloseReasons.closureOf("closed by peer: closed (code 0)"))
        assertEquals(NodeLinkClosure.AUTH_FAILURES, IrohCloseReasons.closureOf("closed by peer: auth_failures (code 4401)"))
        assertEquals(NodeLinkClosure.AUTH_RATE_LIMITED, IrohCloseReasons.closureOf("aborted by peer: auth_rate_limited (code 4429)"))
        assertEquals(NodeLinkClosure(4403L, ""), IrohCloseReasons.closureOf("closed by peer: 4403"))
    }

    @Test
    fun everyCloseTheNodeMakesSurvivesTheText() {
        listOf(
            NodeLinkClosure(4401L, "not_authenticated"),
            NodeLinkClosure(4403L, "peer_revoked"),
            NodeLinkClosure(4403L, "peer_not_allowed"),
            NodeLinkClosure(4403L, "protocol_not_authorized"),
            NodeLinkClosure(4404L, "unknown_alpn"),
            NodeLinkClosure(4400L, "malformed_frame"),
        ).forEach { closure ->
            assertEquals(closure, IrohCloseReasons.closureOf("closed by peer: ${closure.reason} (code ${closure.code})"))
        }
    }

    @Test
    fun aReasonThatItselfLooksLikeACodeKeepsTheRealCode() {
        assertEquals(
            NodeLinkClosure(4403L, "revoked (code 1) by operator"),
            IrohCloseReasons.closureOf("closed by peer: revoked (code 1) by operator (code 4403)"),
        )
    }

    @Test
    fun aCloseWithoutAnApplicationCodeIsLost() {
        listOf(null, "", "timed out", "reset by peer", "closed", "closed by peer: PROTOCOL_VIOLATION: bad frame").forEach {
            assertEquals(NodeLinkClosure.LOST, IrohCloseReasons.closureOf(it), "close text $it")
        }
    }
}
