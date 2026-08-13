package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * letta-mobile-g2d2i: client-mode QR validation.
 *
 * IMPORTANT — trust model (do not "fix" this by adding a signature check):
 * the envelope's `signature` field is an HMAC-SHA256 keyed on the MINTING
 * host's private Iroh secret. HMAC is symmetric, so a scanning phone can
 * never verify it — there is no public verification key to check it against.
 * This mirrors the ZEDRA reference design this epic is patterned on
 * (github.com/tanlethanh/zedra), whose QR carries no signature at all.
 *
 * The real trust anchor is that the QR delivers the host's Ed25519 PUBLIC
 * key (`node_id`) out-of-band via the physical camera scan. The scanner then
 * dials that exact node over Iroh; the QUIC/TLS handshake authenticates the
 * host because the Iroh NodeId *is* the public key. So the scanner's
 * validation is precisely:
 *   (a) [decode] parses (prefix + version + structure) — this file
 *   (b) [decode] checks expiry — this file
 *   (c) dial `nodeIdHex` over Iroh — transport auth proves host identity
 *       (NOT implemented here; a follow-on bead wires the actual dial, since
 *       the app currently has no "connect to a manually/QR-supplied node id"
 *       flow at all — see the PR report for this bead)
 *   (d) server-side one-shot redeem rejects replay — already implemented by
 *       `IrohPairingService.redeem` (d6e8g.5), invoked when (c) lands.
 */
object PairingQrDecoder {

    enum class Reason {
        /** The scanned text isn't a `letta-qr-v1.` payload at all (e.g. a random URL). */
        WRONG_PREFIX,

        /** The payload has the right prefix and decodes to JSON, but declares an unsupported `version`. */
        WRONG_VERSION,

        /** The payload has the right prefix but fails to base64url-decode, isn't JSON, or is missing required fields. */
        MALFORMED,

        /** The payload is well-formed but its `expires_at_ms` is in the past. */
        EXPIRED,
    }

    sealed interface Result {
        data class Valid(val decoded: PairQrEnvelope.Decoded) : Result
        data class Invalid(val reason: Reason) : Result
    }

    private const val PREFIX = "${PairQrEnvelope.SCHEME}."
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Validate a raw string read off a camera frame. [nowMs] is injectable
     * for deterministic expiry tests.
     */
    fun decode(raw: String, nowMs: Long = System.currentTimeMillis()): Result {
        if (!raw.startsWith(PREFIX)) return Result.Invalid(Reason.WRONG_PREFIX)
        if (declaresUnsupportedVersion(raw)) return Result.Invalid(Reason.WRONG_VERSION)
        val decoded = PairQrEnvelope.decode(raw) ?: return Result.Invalid(Reason.MALFORMED)
        if (decoded.expiresAtMs <= nowMs) return Result.Invalid(Reason.EXPIRED)
        return Result.Valid(decoded)
    }

    /**
     * `PairQrEnvelope.decode` returns null uniformly for "wrong version" and
     * "malformed" (missing fields, bad JSON, bad base64). To surface a
     * distinct error message for a version mismatch specifically, we do a
     * light pre-parse here — tolerant of anything that isn't valid enough to
     * read a `version` field, in which case we fall through to the real
     * decoder's MALFORMED classification.
     */
    private fun declaresUnsupportedVersion(raw: String): Boolean {
        val b64 = raw.substring(PREFIX.length)
        val bytes = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return false
        val version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: return false
        return version != PairQrEnvelope.VERSION
    }
}
