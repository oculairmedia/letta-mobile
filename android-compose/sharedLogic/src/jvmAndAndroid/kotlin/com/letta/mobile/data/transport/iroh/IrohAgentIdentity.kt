package com.letta.mobile.data.transport.iroh

import computer.iroh.EndpointId
import computer.iroh.SecretKey
import java.io.File
import java.util.Base64

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
 */
class IrohAgentIdentity private constructor(
    val agentId: String,
    /** Raw 32-byte Ed25519 secret key. Sensitive — keep in memory only. */
    val secretKeyBytes: ByteArray,
    /** Hex-encoded node id (public key) — the dialable identity of this agent. */
    val nodeIdHex: String,
) {
    /** Build an Iroh [SecretKey] handle from the persisted bytes (caller closes it). */
    fun secretKey(): SecretKey = SecretKey.fromBytes(secretKeyBytes)

    companion object {
        /**
         * Load the agent's persisted identity, or create + persist a new one.
         * Idempotent: a second call for the same [agentId] returns the same key.
         *
         * @param dir directory holding per-agent identity files (created 0700).
         */
        fun loadOrCreate(agentId: String, dir: File): IrohAgentIdentity {
            require(agentId.isNotBlank()) { "agentId must not be blank" }
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
