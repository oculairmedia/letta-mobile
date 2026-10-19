package com.letta.mobile.data.pairing

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A pairing invite read off a QR / deep link, decoded
 * from the `letta-qr-v1` envelope (docs/reference/qr-pairing-protocol.md §5/§7).
 *
 * Trust model: the envelope's HMAC signature is host-private and cannot be
 * verified here (protocol §7.3 note). The trust anchor is [nodeId]: the client
 * dials exactly that Ed25519 public key and the QUIC handshake proves the host
 * holds it; the server then enforces one-shot redemption and expiry.
 *
 * [inviteToken] is the one-time `invite:<secret>` credential — never log it.
 */
class ScannedInvite(
    val nodeId: String,
    val inviteToken: String,
    val expiresAtMs: Long,
    /** Non-secret reachability hint from the pairing link (ticket, `node@host:port`, or `host:port`). */
    val dial: String?,
) {
    /** A short, human-checkable fingerprint of the host identity (first 8 hex of the NodeId). */
    val hostFingerprint: String get() = nodeId.take(FINGERPRINT_CHARS)

    override fun toString(): String = "ScannedInvite(node=$hostFingerprint…, <redacted>, expiresAtMs=$expiresAtMs)"

    private companion object {
        const val FINGERPRINT_CHARS = 8
    }
}

/** Why a scanned value is not a usable invite (before any network call). */
enum class InviteFormatError { NOT_A_PAIRING_CODE, UNSUPPORTED_VERSION, MALFORMED }

/** Outcome of [ScannedInviteParser.parse]. */
sealed interface ParsedInvite {
    data class Valid(val invite: ScannedInvite) : ParsedInvite

    data class Invalid(val error: InviteFormatError) : ParsedInvite

    companion object {
        fun valid(invite: ScannedInvite): ParsedInvite = Valid(invite)

        fun invalid(error: InviteFormatError): ParsedInvite = Invalid(error)
    }
}

@OptIn(ExperimentalEncodingApi::class)
object ScannedInviteParser {
    private const val SUPPORTED_VERSION = 1
    private const val INVITE_PREFIX = "invite:"
    private val NODE_ID = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** Accepts a pairing link (`meridian://pair?…`, `letta://pair?…`) or a bare `letta-qr-v1.` envelope. */
    fun parse(raw: String): ParsedInvite {
        val link = PairLink.parse(raw) ?: return ParsedInvite.invalid(InviteFormatError.NOT_A_PAIRING_CODE)
        val body = decodeBody(link.qrInvite.removePrefix(PairLink.QR_SCHEME_PREFIX))
            ?: return ParsedInvite.invalid(InviteFormatError.MALFORMED)
        val version = (body["version"] as? JsonPrimitive)?.intOrNull
            ?: return ParsedInvite.invalid(InviteFormatError.MALFORMED)
        if (version != SUPPORTED_VERSION) return ParsedInvite.invalid(InviteFormatError.UNSUPPORTED_VERSION)
        return toInvite(body, link.dial)?.let(ParsedInvite::valid) ?: ParsedInvite.invalid(InviteFormatError.MALFORMED)
    }

    private fun toInvite(body: JsonObject, dial: String?): ScannedInvite? {
        val nodeId = body.string("node_id")?.lowercase()?.takeIf(NODE_ID::matches) ?: return null
        val token = body.string("signed_secret")?.takeIf { it.startsWith(INVITE_PREFIX) && it.length > INVITE_PREFIX.length }
            ?: return null
        val expiresAt = (body["expires_at_ms"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        return ScannedInvite(nodeId = nodeId, inviteToken = token, expiresAtMs = expiresAt, dial = dial)
    }

    private fun decodeBody(encoded: String): JsonObject? = runCatching {
        json.parseToJsonElement(base64.decode(encoded).decodeToString()) as? JsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
