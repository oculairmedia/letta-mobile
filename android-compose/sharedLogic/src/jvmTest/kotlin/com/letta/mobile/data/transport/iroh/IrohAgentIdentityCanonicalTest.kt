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

    private fun keyB64Of(fileName: String): String =
        Regex("\"secretKeyB64\":\"([^\"]+)\"").find(File(dir, fileName).readText())!!.groupValues[1]

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

    // ---- migration ----

    /**
     * Bare counterpart exists: the orphan is deleted and the LIVE key is untouched.
     * Asserting the surviving key bytes is the point — a migration that "succeeds" by
     * overwriting the live keypair with the orphan's would change the agent's node id
     * and break every peer that cached it.
     */
    @Test
    fun `migration deletes the orphan and preserves the live canonical key`() {
        val liveKey = fixedKeyB64(1)
        writeIdentity("agent-X.json", "agent-X", liveKey)
        writeIdentity("letta_agent-X.json", "letta_agent-X", fixedKeyB64(2))

        val actions = IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)

        assertEquals(listOf("agent-X.json"), identityFiles())
        assertEquals(liveKey, keyB64Of("agent-X.json"), "the live canonical key must survive unchanged")
        val deleted = assertIs<IdentityMigrationAction.DeletedOrphan>(actions.single())
        assertEquals("letta_agent-X", deleted.legacyAgentId)
        assertEquals("agent-X", deleted.canonicalAgentId)
    }

    /**
     * No bare counterpart: the keypair is carried over verbatim. Regenerating instead
     * would silently rotate the agent's node id.
     */
    @Test
    fun `migration renames a lone legacy file preserving key bytes`() {
        val legacyKey = fixedKeyB64(3)
        writeIdentity("letta_agent-Y.json", "letta_agent-Y", legacyKey)

        val actions = IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)

        assertEquals(listOf("agent-Y.json"), identityFiles())
        assertEquals(legacyKey, keyB64Of("agent-Y.json"), "key material must be preserved byte-for-byte")
        assertIs<IdentityMigrationAction.RenamedToCanonical>(actions.single())
    }

    /** The migrated key must still load, and yield the same node id it had before. */
    @Test
    fun `renamed identity keeps its node id`() {
        writeIdentity("letta_agent-Y.json", "letta_agent-Y", fixedKeyB64(4))
        val before = IrohAgentIdentity.loadOrCreate("letta_agent-Y", dir)

        // loadOrCreate canonicalizes, so `before` created agent-Y.json; reset to the
        // pre-migration shape to exercise the migration proper.
        dir.deleteRecursively()
        writeIdentity("letta_agent-Y.json", "letta_agent-Y", Base64.getEncoder().encodeToString(before.secretKeyBytes))

        IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)
        val after = IrohAgentIdentity.loadOrCreate("agent-Y", dir)

        assertEquals(before.nodeIdHex, after.nodeIdHex, "migration must not rotate the node id")
    }

    @Test
    fun `migration is idempotent`() {
        writeIdentity("agent-X.json", "agent-X", fixedKeyB64(1))
        writeIdentity("letta_agent-X.json", "letta_agent-X", fixedKeyB64(2))
        writeIdentity("letta_agent-Y.json", "letta_agent-Y", fixedKeyB64(3))

        val first = IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)
        val filesAfterFirst = identityFiles()
        val second = IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)

        assertEquals(2, first.size)
        assertTrue(second.isEmpty(), "a second run must report no actions: $second")
        assertEquals(filesAfterFirst, identityFiles(), "a second run must not change the directory")
    }

    @Test
    fun `migration leaves canonical and unrelated files untouched`() {
        writeIdentity("agent-X.json", "agent-X", fixedKeyB64(1))
        dir.mkdirs()
        File(dir, "README.md").writeText("not an identity")

        val actions = IrohAgentIdentity.migrateLegacyNamespacedFiles(dir)

        assertTrue(actions.isEmpty())
        assertEquals(listOf("README.md", "agent-X.json"), identityFiles())
    }

    @Test
    fun `migration on a missing directory is a no-op and never throws`() {
        assertTrue(IrohAgentIdentity.migrateLegacyNamespacedFiles(File(dir, "nope")).isEmpty())
    }
}
