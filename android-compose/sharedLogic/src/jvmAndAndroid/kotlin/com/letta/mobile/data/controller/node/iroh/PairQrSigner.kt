package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * letta-mobile-gw0h1: the QR issuance seam for the `pair.invite.create` extension
 * defined by `reference/qr-pairing-protocol.md` (doctrine 11, gate bead 7b9on).
 *
 * The wire format is `letta-qr-v1.<base64url-json>` whose body is
 * `{ version, node_id, signed_secret, expires_at_ms, signature }`. The signature
 * MUST cover `version`, `node_id`, `signed_secret`, and `expires_at_ms` together
 * (protocol §5.1, §7.3). The canonical signing message is the compact JSON
 * `{"version":1,"node_id":"<id>","signed_secret":"<secret>","expires_at_ms":<ms>}`
 * with members in that exact order and no insignificant whitespace.
 *
 * `PairQrSigner` is the seam between the pairing handler and the minting
 * identity. The default implementation is a DETERMINISTIC HMAC-SHA256 keyed
 * by `IrohSecretKeyStore.loadOrCreate()` (the same 32-byte SecRandom key that
 * backs the endpoint). v1 acceptance criteria treat this as a private signing
 * surface: the QR is only useful to a phone/desktop that already knows the
 * minting identity through the QR itself (`node_id` is the public-key
 * reference), so the real Ed25519 verification is a follow-on bead
 * (letta-mobile-nonza / letta-mobile-g2d2i). The wire format is identical
 * either way: a base64url-encoded signature blob over the canonical message.
 *
 * seealso: `reference/qr-pairing-protocol.md` §5, §7, §10.
 */
fun interface PairQrSigner {
    /**
     * Sign the canonical QR message for the given minting node + secret + expiry.
     * Implementations MUST return a base64url-NO-PAD string (the protocol's
     * permissive variant — see `letta-qr-v1` decoder rules in §7.1).
     */
    fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String
}

/**
 * Inert signer used when the wrapper is not configured to mint QR invites.
 * Returning the empty string lets the handler emit a `qr_invite: null` field
 * (additive, backward-compatible) without surfacing a fake: it is the protocol
 * doc's "unknown legacy field" tolerated behaviour.
 */
object NoOpPairQrSigner : PairQrSigner {
    override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String = ""
}

/**
 * Default HMAC-SHA256 signer keyed by the wrapper's persistent Iroh identity.
 *
 * The key is the full 32-byte `IrohSecretKeyStore.loadOrCreate()` payload, so
 * the QR payload is bound to the same identity that dials the wrapper. A
 * different minting node (different `node_id`) yields a different signature
 * even for the same secret; replay across restarts stays bound because the
 * secret is itself rotated per invite by `IrohPairingService.createInvite`.
 *
 * `nodeIdHex` is still part of the canonical message so a future version that
 * derives the verification key from `node_id` alone (Ed25519 over the public
 * key) drops in without altering the on-wire format.
 */
class HmacPairQrSigner(private val secretKeyStore: IrohSecretKeyStore) : PairQrSigner {
    override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String {
        val keyBytes = runCatching {
            // block on the suspending key load — this signer is called from a
            // synchronous handler closure (`buildJsonObject { ... }`); the
            // IrohSecretKeyStore contract is `suspend` so the bridge uses
            // runBlocking. The store is a tiny file read or a SecureRandom
            // allocation, so the dispatcher overhead is negligible.
            kotlinx.coroutines.runBlocking { secretKeyStore.loadOrCreate() }
        }.getOrElse { t ->
            // Doctrine: do not surface signer misconfiguration as a fatal
            // RPC failure on the QR path. The non-QR fields still mint, and
            // a follow-up retry with a rekeyed store succeeds.
            Telemetry.event(
                "PairQr", "sign.key_load_failed",
                "reason" to (t.message ?: t::class.simpleName ?: "error"),
                level = Telemetry.Level.WARN,
            )
            return ""
        }
        return hmacSha256Base64UrlNoPad(keyBytes, canonicalMessage(nodeIdHex, signedSecret, expiresAtMs))
    }

    companion object {
        /**
         * The canonical signed message bytes: compact JSON with members in
         * protocol-declared order and no whitespace. Mirrors the protocol
         * doc §5.1 verbatim.
         */
        internal fun canonicalMessage(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): ByteArray {
            // Manual compact JSON to avoid relying on kotlinx.serialization
            // ordering (which is insertion-order, but explicit construction
            // is the contract the protocol doc pins).
            val sb = StringBuilder(64 + nodeIdHex.length + signedSecret.length)
            sb.append("{\"version\":1,\"node_id\":\"")
            sb.append(nodeIdHex)
            sb.append("\",\"signed_secret\":\"")
            sb.append(signedSecret)
            sb.append("\",\"expires_at_ms\":")
            sb.append(expiresAtMs)
            sb.append('}')
            return sb.toString().encodeToByteArray()
        }

        private fun hmacSha256Base64UrlNoPad(key: ByteArray, message: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val raw = mac.doFinal(message)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        }
    }
}

/**
 * letta-mobile-gw0h1: encode the versioned QR payload per protocol §5.1 +
 * §7.1. Pure function — exposed to the CLI renderer and to the tests.
 *
 * `version` is hard-coded to `1`; bumping it is a protocol version bump and
 * requires a new branch. The function is intentionally tolerant of `signature
 * == ""` so callers can build a payload first and sign after (the desktop
 * bead pairs this with a real Ed25519 signer).
 */
object PairQrEnvelope {
    const val SCHEME: String = "letta-qr-v1"
    const val VERSION: Int = 1

    /**
     * Build the on-wire `letta-qr-v1.<base64url-json>` string. The JSON body
     * uses the canonical compact ordering declared by the protocol doc.
     */
    fun encode(
        nodeIdHex: String,
        signedSecret: String,
        expiresAtMs: Long,
        signature: String,
    ): String {
        val json = buildCanonicalJson(nodeIdHex, signedSecret, expiresAtMs, signature)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
        return "$SCHEME.$b64"
    }

    private fun buildCanonicalJson(
        nodeIdHex: String,
        signedSecret: String,
        expiresAtMs: Long,
        signature: String,
    ): String {
        // Escaping rules: a control char / quote / backslash inside any string
        // field breaks the contract. The invite secret is hex (`IrohPairingService`
        // emits `%02x` bytes) and the node id is hex, so the only realistic
        // escape target is the signature (base64url allows '-', '_' — both
        // safe). Defensive JSON escaping keeps this safe for future signers.
        val sb = StringBuilder(96 + nodeIdHex.length + signedSecret.length + signature.length)
        sb.append("{\"version\":").append(VERSION)
        sb.append(",\"node_id\":\"").append(escapeJsonString(nodeIdHex)).append('"')
        sb.append(",\"signed_secret\":\"").append(escapeJsonString(signedSecret)).append('"')
        sb.append(",\"expires_at_ms\":").append(expiresAtMs)
        sb.append(",\"signature\":\"").append(escapeJsonString(signature)).append('"')
        sb.append('}')
        return sb.toString()
    }

    private fun escapeJsonString(value: String): String {
        if (value.indexOfAny(charArrayOf('"', '\\', '\n', '\r', '\t', '\b', '\u000c')) < 0) return value
        val sb = StringBuilder(value.length + 8)
        for (c in value) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Decode the wire format back into its parts. Returns null on any
     * structural violation (missing prefix, bad base64url, non-JSON body,
     * wrong version, missing required fields).
     *
     * Exposed for symmetry: the cross-platform scanner (letta-mobile-g2d2i)
     * will reuse this decoder. Tests for both, in `jvmTest`.
     */
    fun decode(encoded: String): Decoded? {
        if (!encoded.startsWith("$SCHEME.")) return null
        val b64 = encoded.substring(SCHEME.length + 1)
        val body = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val text = body.toString(Charsets.UTF_8)
        if (text.length < 2 || text.first() != '{' || text.last() != '}') return null
        // We delegate to kotlinx.serialization: the body is a well-formed
        // compact JSON object. The protocol doc says the decoder "must reject
        // missing fields" and "ignore unknown fields only if running a
        // compatibility policy" — we accept the strict (v1) policy by
        // requiring all five logical fields below.
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return null
        val version = (obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: return null
        if (version != VERSION) return null
        val nodeId = (obj["node_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
        val signedSecret = (obj["signed_secret"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
        val expiresAtMs = (obj["expires_at_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: return null
        val signature = (obj["signature"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return null
        return Decoded(version, nodeId, signedSecret, expiresAtMs, signature)
    }

    data class Decoded(
        val version: Int,
        val nodeIdHex: String,
        val signedSecret: String,
        val expiresAtMs: Long,
        val signature: String,
    )

    // Re-export the canonical message builder so callers verifying a signature
    // (decoder path on the mobile / desktop) can recompute the same bytes.
    fun canonicalMessageBytes(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): ByteArray =
        HmacPairQrSigner.canonicalMessage(nodeIdHex, signedSecret, expiresAtMs)
}

/**
 * letta-mobile-gw0h1: tiny fingerprint helper for operators / log lines that
 * want to confirm a QR payload matches a known node id without decoding the
 * full envelope. NOT a substitute for the protocol's signature verification.
 */
internal fun qrPayloadDigest(encoded: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val raw = md.digest(encoded.encodeToByteArray())
    return raw.joinToString("") { b -> "%02x".format(b) }.substring(0, 16)
}
