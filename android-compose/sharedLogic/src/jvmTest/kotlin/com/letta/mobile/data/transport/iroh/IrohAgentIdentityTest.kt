package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bn008.1: per-agent identity load-or-create must be idempotent
 * (same agentId twice => same key + same node id). Uses the real iroh SecretKey.
 */
class IrohAgentIdentityTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "bn008-id-${System.nanoTime()}")

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test
    fun loadOrCreateIsIdempotentForSameAgent() {
        val first = IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val second = IrohAgentIdentity.loadOrCreate("agent-A", dir)
        assertTrue(first.secretKeyBytes.contentEquals(second.secretKeyBytes), "same agentId must reuse the persisted key")
        assertEquals(first.nodeIdHex, second.nodeIdHex, "same key => same node id")
        assertTrue(first.nodeIdHex.isNotBlank())
    }

    @Test
    fun distinctAgentsGetDistinctIdentities() {
        val a = IrohAgentIdentity.loadOrCreate("agent-A", dir)
        val b = IrohAgentIdentity.loadOrCreate("agent-B", dir)
        assertTrue(!a.secretKeyBytes.contentEquals(b.secretKeyBytes))
        assertTrue(a.nodeIdHex != b.nodeIdHex)
    }

    @Test
    fun identityFileIsCreatedPerAgent() {
        IrohAgentIdentity.loadOrCreate("agent-A", dir)
        assertTrue(File(dir, "agent-A.json").exists())
    }
}
