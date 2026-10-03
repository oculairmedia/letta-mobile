package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginApi
import kotlinx.serialization.json.Json

/**
 * LCP wire v1 (plan section 5, letta-mobile-s416w.25): the Letta Canvas Plugin protocol a `process`
 * plugin speaks as NDJSON on stdio and a `service` plugin speaks as WebSocket text frames. JSON-RPC
 * 2.0, UTF-8, one message per line or frame. The reference for authors is
 * `docs/reference/canvas-plugin-wire-v1.md`; the golden transcripts are under
 * `commonTest/resources/canvas/plugin/v1/wire/`. The method set, deadlines and size limits are
 * `:plugin-api`'s [LcpMethod]; the message shapes are its DTOs wherever the SPI has one.
 */
object LcpWire {
    /** The JSON-RPC version every message carries in `jsonrpc`. */
    const val JSONRPC: String = "2.0"

    /** The contract version this host speaks best; [SUPPORTED_CONTRACT_VERSIONS] is all it accepts. */
    const val CONTRACT_VERSION: Int = PluginApi.CONTRACT_VERSION

    /** Every contract version this host can speak, offered in `plugin.initialize`. */
    val SUPPORTED_CONTRACT_VERSIONS: List<Int> = listOf(CONTRACT_VERSION)

    /** The largest message, in UTF-8 bytes, either side sends or accepts (4 MiB). */
    const val MAX_MESSAGE_BYTES: Int = LcpMethod.MAX_MESSAGE_BYTES

    /** The most decoded bytes one `host.putAsset.chunk` carries (1 MiB). */
    const val MAX_CHUNK_BYTES: Int = LcpMethod.MAX_ASSET_CHUNK_BYTES

    /** The longest `base64` text of one chunk: [MAX_CHUNK_BYTES] encoded, padded. */
    const val MAX_CHUNK_BASE64_CHARS: Int = (MAX_CHUNK_BYTES + 2) / 3 * 4

    /** The largest asset one upload may declare and deliver (8 MiB, the snapshot cap). */
    const val MAX_ASSET_BYTES: Long = 8L * 1024 * 1024

    /** How many uploads one plugin may hold open at once. */
    const val MAX_OPEN_UPLOADS: Int = 4

    /** How many requests a peer runs for the other side at once; more are answered [LcpErrorCode.OVERLOADED]. */
    const val MAX_CONCURRENT_INBOUND: Int = 16

    /** How many of its own requests a peer keeps waiting at once; a caller beyond that suspends. */
    const val MAX_OUTSTANDING_OUTBOUND: Int = 16

    /**
     * The one JSON configuration of the wire: unknown fields are ignored (a later minor version may
     * add some), absent optional fields stay absent, sealed shapes are tagged by `type` (as
     * `:plugin-api`'s DTOs are; `plugin.health` by `status`).
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
}

/**
 * JSON-RPC error codes on the wire: the five of JSON-RPC 2.0, `-32800` as in LSP, and LCP's own in
 * the server range. [ACTION_FAILED] carries the plugin's own error code in `data.code`.
 */
object LcpErrorCode {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603
    const val REQUEST_CANCELLED: Int = -32800

    const val DEADLINE_EXCEEDED: Int = -32001
    const val NOT_INITIALIZED: Int = -32002
    const val SESSION_STATE: Int = -32003
    const val CONTRACT_MISMATCH: Int = -32004
    const val MESSAGE_TOO_LARGE: Int = -32005
    const val CAPABILITY_REFUSED: Int = -32006
    const val SECRET_REFUSED: Int = -32007
    const val OVERLOADED: Int = -32008
    const val CLOSED: Int = -32009
    const val ACTION_FAILED: Int = -32010
    const val UPLOAD_REFUSED: Int = -32011

    /** Every code with the one-line meaning the reference doc lists. */
    val meanings: Map<Int, String> = mapOf(
        PARSE_ERROR to "the line or frame is not JSON",
        INVALID_REQUEST to "not a JSON-RPC 2.0 message LCP accepts (batches included)",
        METHOD_NOT_FOUND to "no such method in this direction",
        INVALID_PARAMS to "params do not hold to the method's shape",
        INTERNAL_ERROR to "the handler failed unexpectedly",
        REQUEST_CANCELLED to "the caller sent \$/cancel for this request",
        DEADLINE_EXCEEDED to "the method's deadline passed",
        NOT_INITIALIZED to "plugin.initialize has not completed",
        SESSION_STATE to "the session's state refuses the method (stopping, closed, already initialized)",
        CONTRACT_MISMATCH to "no contract version both sides speak",
        MESSAGE_TOO_LARGE to "the message exceeds 4 MiB",
        CAPABILITY_REFUSED to "the plugin lacks the capability the call needs (data.capability)",
        SECRET_REFUSED to "the message held a secret, so it was refused whole",
        OVERLOADED to "too many requests in flight; retry later",
        CLOSED to "the session or transport is closed",
        ACTION_FAILED to "the action failed; data.code is the plugin's own code",
        UPLOAD_REFUSED to "an asset upload broke its limits, order or hash",
    )
}
