package com.letta.mobile.data.transport.iroh

import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-oi147: an agent has exactly ONE Iroh identity, regardless of which
 * namespace spelling the caller uses.
 *
 * Before this, `agent-X` and `letta_agent-X` derived two different Ed25519 keypairs
 * (the live host carried four keypairs for two agents). The node id IS the identity
 * on the wire, so that is not a cosmetic duplicate — it is one principal holding two
 * credentials, and whichever one gets loaded determines who the agent *is* to a peer.
 *
 * Note what is deliberately NOT tested here: a fallback that loads the prefixed file
 * when the bare one is missing. That behaviour is rejected by design — see
 * `canonicalization is not a fallback probe` below.
 */
class IrohAgentIdentityCanonicalTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "oi147-id-${System.nanoTime()}")

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun writeIdentity(fileName: String, agentId: String, keyB64: String) {
        dir.mkdirs()
        File(dir, fileName).writeText("""{"agentId":"$agentId","secretKeyB64":"$keyB64"}""")
    }

    private fun fixedKeyB64(seed: Byte): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { seed })

    private fun identityFiles(): List<String> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name }.sorted()

    @Test
    fun `both namespace spellings yield the same identity`() {
        val viaPrefixed = IrohAgentIdentity.loadOrCreate("letta_agent-X", dir)
        val viaBare = IrohAgentIdentity.loadOrCreate("agent-X", dir)

        assertTrue(
            viaPrefixed.secretKeyBytes.contentEquals(viaBare.secretKeyBytes),
            "prefixed and bare spellings must share one keypair",
        )
        assertEquals(viaPrefixed.nodeIdHex, viaBare.nodeIdHex, "same key => same node id")
    }

    @Test
    fun `exactly one file is written whichever spelling is used first`() {
        IrohAgentIdentity.loadOrCreate("letta_agent-X", dir)
        IrohAgentIdentity.loadOrCreate("agent-X", dir)

        assertEquals(listOf("agent-X.json"), identityFiles())
    }

    @Test
    fun `persisted agentId field is canonical even when called with the prefix`() {
        IrohAgentIdentity.loadOrCreate("letta_agent-X", dir)

        val text = File(dir, "agent-X.json").readText()
        assertTrue(text.contains("\"agentId\":\"agent-X\""), "persisted agentId must be the bare form: $text")
    }

    @Test
    fun `exposed agentId is canonical`() {
        assertEquals("agent-X", IrohAgentIdentity.loadOrCreate("letta_agent-X", dir).agentId)
    }

    /**
     * The security property, stated as a test: a missing canonical file must produce a
     * NEW key, never silently adopt the legacy prefixed file's key at load time. Load
     * time is the wrong place to reconcile credentials — reconciliation is the explicit
     * migration below, which is auditable. If this ever starts failing because someone
     * added a fallback probe, that is the regression this test exists to catch.
     */
    @Test
    fun `canonicalization is not a fallback probe`() {
        val legacyKey = fixedKeyB64(7)
        writeIdentity("letta_agent-X.json", "letta_agent-X", legacyKey)

        val loaded = IrohAgentIdentity.loadOrCreate("agent-X", dir)

        assertFalse(
            Base64.getEncoder().encodeToString(loaded.secretKeyBytes) == legacyKey,
            "load must not adopt a legacy prefixed key — that is impersonation, not resolution",
        )
        assertTrue(File(dir, "agent-X.json").exists(), "a fresh canonical identity should have been created")
    }

    @Test
    fun `blank agent id is rejected`() {
        val ex = runCatching { IrohAgentIdentity.loadOrCreate("   ", dir) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex)
    }

    /** `letta_` alone canonicalizes to empty — must be rejected, not written as ".json". */
    @Test
    fun `bare prefix with no id is rejected`() {
        val ex = runCatching { IrohAgentIdentity.loadOrCreate("letta_", dir) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex)
    }

}
