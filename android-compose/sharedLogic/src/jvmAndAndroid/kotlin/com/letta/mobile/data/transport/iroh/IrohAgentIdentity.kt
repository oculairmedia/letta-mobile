package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.model.AgentIdNamespace
import computer.iroh.EndpointId
import computer.iroh.SecretKey
import java.io.File
import java.util.Base64

/**
 * letta-mobile-oi147: one action taken by [IrohAgentIdentity.migrateLegacyNamespacedFiles].
 * Returned rather than logged internally so the caller owns the audit surface —
 * this records changes to SECRET KEY MATERIAL and must never happen silently.
 */
sealed interface IdentityMigrationAction {
    val legacyAgentId: String

    /** A canonical file already held this agent's live keypair; the orphan was removed. */
    data class DeletedOrphan(override val legacyAgentId: String, val canonicalAgentId: String) : IdentityMigrationAction

    /** No canonical file existed; the keypair was preserved verbatim under the bare name. */
    data class RenamedToCanonical(override val legacyAgentId: String, val canonicalAgentId: String) : IdentityMigrationAction

    /** The file could not be migrated. Left in place — never leave a key half-moved. */
    data class Failed(override val legacyAgentId: String, val reason: String) : IdentityMigrationAction
}

/**
 * letta-mobile-oi147: a legacy `letta_`-prefixed identity file paired with the
 * canonical file it should collapse onto. Keeping the two names and the two Files
 * together as one value means the migration steps cannot mix up which side is which
 * — the failure mode here would overwrite a live secret key with an orphan's.
 */
private data class LegacyIdentityFile(
    val legacyId: String,
    val canonicalId: String,
    val legacyFile: File,
    val canonicalFile: File,
) {
    companion object {
        private const val SUFFIX = ".json"

        /** Null when the file name canonicalizes to an empty id (e.g. bare `letta_.json`). */
        fun of(dir: File, legacy: File): LegacyIdentityFile? {
            val legacyId = legacy.name.removeSuffix(SUFFIX)
            val canonicalId = AgentIdNamespace.normalizeToBareId(legacyId)
            if (canonicalId.isBlank()) return null
            return LegacyIdentityFile(
                legacyId = legacyId,
                canonicalId = canonicalId,
                legacyFile = legacy,
                canonicalFile = File(dir, "$canonicalId$SUFFIX"),
            )
        }
    }
}

/**
 * letta-mobile-bn008.1: per-agent persistent Iroh identity.
 *
 * Each agent gets a stable Ed25519 [SecretKey] (the Iroh identity), persisted
 * locally per agentId — mirroring the real-a2a `load_or_create_identity` pattern
 * (generate + save JSON, reused across sessions). The node id is the public key.
 * Secret keys are sensitive: they live in a 0600 file keyed by agentId, never
 * plaintext in Postgres.
 *
 * load-or-create is idempotent: the same agentId always yields the same key bytes.
 *
 * letta-mobile-oi147: the agentId is CANONICALIZED ([AgentIdNamespace.normalizeToBareId])
 * before it becomes a filename, so `agent-X` and `letta_agent-X` are one identity.
 * Without this, the two spellings derived two different Ed25519 keypairs — the live
 * host carried four keypairs for two agents.
 *
 * This deliberately does NOT resolve by probing (try bare, then fall back to the
 * prefixed file). The node id IS the identity on the wire, so a fallback would mean
 * "failed to load key A, so silently sign as principal B" — an agent that does not
 * degrade but impersonates. Authentication lookups canonicalize first and then match
 * exactly. Legacy prefixed files are handled ONCE by [migrateLegacyNamespacedFiles],
 * an explicit auditable migration, not by a lookup-time fallback.
 */
class IrohAgentIdentity private constructor(
    val agentId: String,
    /** Raw 32-byte Ed25519 secret key. Sensitive — keep in memory only. */
    val secretKeyBytes: ByteArray,
    /** Hex-encoded node id (public key) — the dialable identity of this agent. */
    val nodeIdHex: String,
) {
    companion object {
        /**
         * Load the agent's persisted identity, or create + persist a new one.
         * Idempotent: a second call for the same [agentId] returns the same key.
         *
         * [agentId] is canonicalized before use, so the bare and `letta_`-prefixed
         * spellings share one keypair and one file. There is no fallback probe of
         * the other spelling — see the class comment.
         *
         * @param dir directory holding per-agent identity files (created 0700).
         */
        fun loadOrCreate(rawAgentId: String, dir: File): IrohAgentIdentity {
            require(rawAgentId.isNotBlank()) { "agentId must not be blank" }
            val agentId = AgentIdNamespace.normalizeToBareId(rawAgentId)
            require(agentId.isNotBlank()) { "agentId must not be blank after canonicalization" }
            if (!dir.exists()) {
                dir.mkdirs()
                restrictDir(dir)
            }
            val file = File(dir, "$agentId.json")
            if (file.exists()) {
                val persisted = parse(file.readText())
                if (persisted != null) {
                    val bytes = Base64.getDecoder().decode(persisted)
                    val nodeIdHex = nodeIdHexFromSecret(bytes)
                    return IrohAgentIdentity(agentId, bytes, nodeIdHex)
                }
                // Corrupt/unreadable file: regenerate below (do not throw).
            }
            val generated = SecretKey.generate()
            val bytes = generated.use { it.toBytes() }
            val nodeIdHex = nodeIdHexFromSecret(bytes)
            val json = """{"agentId":${quote(agentId)},"secretKeyB64":${quote(Base64.getEncoder().encodeToString(bytes))}}"""
            file.writeText(json)
            restrictFile(file)
            return IrohAgentIdentity(agentId, bytes, nodeIdHex)
        }

        /**
         * letta-mobile-oi147: one-shot migration of identity files written under the
         * retired `letta_`-prefixed namespace, so a given agent ends up with exactly
         * one keypair under its canonical (bare) name.
         *
         * This exists because canonicalizing [loadOrCreate] alone would silently
         * strand the prefixed files: still on disk, holding a DIFFERENT keypair, no
         * longer read by anything. That is key material with no owner — precisely the
         * state an audit should never find.
         *
         * Rules, and why:
         *  - Bare counterpart exists -> DELETE the prefixed orphan. Bare is the form
         *    the live publish path writes, so it is the keypair peers currently see.
         *    Keeping the loser would leave a second credential for one principal.
         *  - No bare counterpart -> RENAME prefixed to bare, preserving the key bytes
         *    verbatim. A regenerate here would silently change the agent's node id and
         *    break every peer that cached it; a rename keeps that identity valid.
         *  - Anything else (already-bare files, unrelated files) is left untouched.
         *
         * Idempotent: a second run over a canonical directory changes nothing and
         * reports nothing. Never throws — a migration failure must not take the
         * wrapper down at bind time; the per-file outcome is reported instead.
         *
         * @return an audit trail of what was done, one entry per file acted on.
         */
        fun migrateLegacyNamespacedFiles(dir: File): List<IdentityMigrationAction> {
            if (!dir.isDirectory) return emptyList()
            val files = dir.listFiles() ?: return emptyList()
            return files.asSequence()
                .filter { it.isFile && it.name.endsWith(SUFFIX) }
                .filter { it.name.startsWith(LEGACY_PREFIX) }
                .sortedBy { it.name } // deterministic audit order
                .mapNotNull { legacy -> migrateOne(dir, legacy) }
                .toList()
        }

        /** Migrate a single legacy file. Returns null when its id canonicalizes to nothing. */
        private fun migrateOne(dir: File, legacy: File): IdentityMigrationAction? {
            val candidate = LegacyIdentityFile.of(dir, legacy) ?: return null
            return runCatching {
                if (candidate.canonicalFile.exists()) candidate.deleteOrphan()
                else candidate.adoptAsCanonical()
            }.getOrElse { IdentityMigrationAction.Failed(candidate.legacyId, it::class.simpleName ?: "error") }
        }

        /** The canonical file already holds this agent's live keypair — drop the duplicate. */
        private fun LegacyIdentityFile.deleteOrphan(): IdentityMigrationAction =
            if (legacyFile.delete()) IdentityMigrationAction.DeletedOrphan(legacyId, canonicalId)
            else IdentityMigrationAction.Failed(legacyId, "delete_failed")

        /**
         * No canonical file exists — carry the key bytes over verbatim.
         *
         * Copy-then-delete rather than renameTo: a half-completed rename would lose
         * the only copy of a secret key. If the delete fails after a successful copy,
         * the canonical file already holds the key and the next run cleans up.
         */
        private fun LegacyIdentityFile.adoptAsCanonical(): IdentityMigrationAction {
            canonicalFile.writeText(legacyFile.readText())
            restrictFile(canonicalFile)
            return if (legacyFile.delete()) IdentityMigrationAction.RenamedToCanonical(legacyId, canonicalId)
            else IdentityMigrationAction.Failed(legacyId, "delete_after_copy_failed")
        }

        private const val SUFFIX = ".json"
        private const val LEGACY_PREFIX = "letta_"

        private fun nodeIdHexFromSecret(secretBytes: ByteArray): String {
            SecretKey.fromBytes(secretBytes).use { key ->
                val endpointId: EndpointId = key.public()
                return endpointId.use { id -> id.toBytes().joinToString("") { b -> "%02x".format(b) } }
            }
        }

        /** Minimal, dependency-free extraction of the secretKeyB64 field. */
        private fun parse(text: String): String? {
            val marker = "\"secretKeyB64\""
            val i = text.indexOf(marker)
            if (i < 0) return null
            val colon = text.indexOf(':', i + marker.length)
            if (colon < 0) return null
            val q1 = text.indexOf('"', colon + 1)
            if (q1 < 0) return null
            val q2 = text.indexOf('"', q1 + 1)
            if (q2 < 0) return null
            return text.substring(q1 + 1, q2)
        }

        private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun restrictDir(dir: File) {
            runCatching { dir.setReadable(false, false); dir.setReadable(true, true); dir.setExecutable(false, false); dir.setExecutable(true, true) }
        }

        private fun restrictFile(file: File) {
            // 0600: owner read/write only.
            runCatching { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true) }
        }
    }
}
