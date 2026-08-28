package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.HmacPairQrSigner
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.qr.QrCode
import com.letta.mobile.qr.QrRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * letta-mobile-gw0h1 (sixv8.2): the CLI QR-rendering subcommand for the
 * `pair.invite.create` extension defined in `reference/qr-pairing-protocol.md`.
 *
 * Renders the wire format `letta-qr-v1.<base64url-json>` (protocol §5.1 + §7.1)
 * produced by the in-process `PairingAdminHandlers` registration, with two
 * output modes:
 *
 *  - **text** (default): Unicode half-block characters on stdout. Works in any
 *    modern TTY. Each line covers two QR rows so the on-screen aspect ratio
 *    stays close to 1:1 even with non-square monospace cells.
 *  - **png**: writes a PNG file (8-bit grayscale, scale=8 pixels per module,
 *    4-module quiet zone). The byte stream is verified by `file` to be
 *    `PNG image data, WxH, 8-bit grayscale, non-interlaced`.
 *
 * The wire format is the protocol's contract — the CLI is the renderer only.
 * `pair.qr` is NOT introduced (per protocol §4 / §10). Existing CLI consumers
 * of `pair.invite.create` (e.g. `meridian peer invite`) are unaffected: the
 * QR field is additive and only emitted when the caller sets `qr: true`.
 *
 * Usage:
 *   ```
 *   meridian-iroh-wrapper pair                                # text mode, stdout
 *   meridian-iroh-wrapper pair --qr-format text                # explicit text
 *   meridian-iroh-wrapper pair --qr-format png=/tmp/qr.png     # headless install
 *   meridian-iroh-wrapper pair --name "alice-mac" --ttl-ms 600000
 *   ```
 *
 * Identity: the wrapper's Iroh secret-key file (`--iroh-secret-key-file`,
 * `LETTA_IROH_SECRET_KEY_FILE`) is the signing key. When unset, an ephemeral
 * key is generated — the QR is still well-formed and scannable, but the
 * node-id the phone sees rotates on every CLI invocation. Operators
 * pairing against a STABLE server (the production wrapper) keep
 * `--iroh-secret-key-file` set so the same node-id is bound to the same
 * QR across re-runs.
 *
 * Storage: `--pairing-store-file` (env: `LETTA_IROH_PAIRING_STORE`) is
 * reused from `app-server-serve-iroh` so a single CLI run can mint a QR
 * and the same store keeps the redemption record when the phone dials in.
 */
class PairCommand : CliktCommand(name = "pair") {

    override fun help(context: Context): String = """
        Mint a QR-encoded pairing invite (letta-mobile-gw0h1 / sixv8.2).

        Renders the wire format `letta-qr-v1.<base64url-json>` (see
        reference/qr-pairing-protocol.md §5.1 + §7.1) produced by the
        in-process `pair.invite.create` admin RPC with `qr: true`. The
        command is the renderer only; the wire format is the protocol's
        contract and is identical for CLI, Desktop, and Mobile.

        Output modes:
          --qr-format text              Unicode half-block QR to stdout (default).
          --qr-format png=<path>        PNG file at the given path (headless install).

        Examples:
          meridian-iroh-wrapper pair
          meridian-iroh-wrapper pair --qr-format text
          meridian-iroh-wrapper pair --qr-format png=/tmp/qr.png
          meridian-iroh-wrapper pair --name "alice-mac" --ttl-ms 600000
    """.trimIndent()

    private val qrFormat by option(
        "--qr-format",
        help = """
            Output mode. Either `text` (default — Unicode half-block QR to
            stdout) or `png=<path>` (PNG file for headless install).
        """.trimIndent(),
    ).default("text").validate {
        require(it == "text" || it.startsWith("png=")) {
            "--qr-format must be 'text' or 'png=<path>', got: $it"
        }
    }

    private val name by option(
        "--name",
        help = "Suggested device label (defaults to 'paired-peer').",
    )

    private val ttlMs by option(
        "--ttl-ms",
        help = "Invite lifetime in milliseconds (defaults to the pairing service default).",
    )

    private val irohSecretKeyFile by option(
        "--iroh-secret-key-file",
        envvar = "LETTA_IROH_SECRET_KEY_FILE",
        help = "Path to the wrapper's 32-byte Iroh secret-key file. The QR is " +
            "signed with this key. Defaults to ephemeral when unset (node id " +
            "rotates on every CLI invocation).",
    )

    private val pairingStoreFile by option(
        "--pairing-store-file",
        envvar = "LETTA_IROH_PAIRING_STORE",
        help = "Optional paired-peer JSON store. When set, the QR mint shares " +
            "the same store as `app-server-serve-iroh`, so a phone that scans " +
            "the QR and dials the wrapper gets the same redemption record.",
    )

    private val quiet by option(
        "--quiet",
        help = "Suppress the `letta-qr-v1:` header line on stdout (PNG mode only).",
    ).default("false")

    override fun run() = runBlocking {
        // Step 1: build the in-process admin router. PairingAdminHandlers
        // takes a `pairing` service; the QR extension takes a `qrSigner` and
        // a `qrNodeIdHex`. We synthesise the node id from the secret-key
        // file path so the same secret-key store produces a stable id across
        // CLI invocations.
        val keyStore = resolveSecretKeyStore(irohSecretKeyFile)
        val qrNodeIdHex = PairNodeIdFactory.fromSecretKeyStore(keyStore)
        val signer: PairQrSigner = HmacPairQrSigner(keyStore)
        val store = pairingStoreFile
            ?.takeIf { it.isNotBlank() }
            ?.let { java.nio.file.Path.of(it) }
        val pairing = IrohPairingService(
            store = store?.let { com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore(it) }
                ?: InMemoryPairedPeerStore(),
        )
        val router = AdminRpcRouter().also { r ->
            PairingAdminHandlers.register(r, pairing, signer, qrNodeIdHex)
        }

        // Step 2: dispatch the `pair.invite.create` RPC with `qr: true`. The
        // handler is synchronous in this code path (in-process dispatch), so
        // we can use `runBlocking` over the suspending dispatch without a
        // thread-pool cost.
        val params = buildJsonObject {
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            ttlMs?.toLongOrNull()?.let { put("ttl_ms", it) }
            put("qr", true)
        }
        val response = router.dispatch(
            com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation(
                requestId = "pair-cli-${System.nanoTime()}",
                method = "pair.invite.create",
                params = params,
                context = com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext.Authenticated,
            ),
        )
        val parsed = parseAdminResponse(response)
        if (!parsed.success) {
            // Doctrine 55b: surface wrapper RPC failures verbatim, do NOT
            // swallow with runCatching.
            System.err.println("pair.invite.create failed: ${parsed.error ?: "unknown error"}")
            exitProcess(1)
        }
        val qrInvite = parsed.qrInvite
        if (qrInvite.isNullOrBlank()) {
            System.err.println("pair.invite.create returned no qr_invite (signer misconfigured?)")
            exitProcess(1)
        }
        // Step 3: render. The renderer is the ONLY difference between CLI,
        // Desktop, and Mobile per protocol §10.
        val mode = parseQrFormat(qrFormat)
        when (mode) {
            is QrFormatSpec.Text -> {
                val matrix = QrCode.encode(qrInvite)
                val text = QrRenderer.renderText(matrix)
                if (quiet != "true") {
                    // Print the wire value once for log/debug, then the
                    // human-renderable QR. Two outputs, easy to diff in CI.
                    println("Wire: $qrInvite")
                }
                print(text)
                println()
            }
            is QrFormatSpec.Png -> {
                val matrix = QrCode.encode(qrInvite)
                val file = mode.file
                val bytes = QrRenderer.writePng(matrix, file)
                if (quiet != "true") {
                    println("Wrote $bytes bytes to ${file.absolutePath}")
                    println("Wire: $qrInvite")
                }
            }
        }
    }

    /**
     * Resolve the operator's `--iroh-secret-key-file` (or `LETTA_IROH_SECRET_KEY_FILE`)
     * into a concrete [IrohSecretKeyStore]. An unset / blank value falls
     * through to a random ephemeral key — the QR remains valid, but the
     * `node_id` is non-deterministic.
     */
    private suspend fun resolveSecretKeyStore(path: String?): com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore {
        if (path.isNullOrBlank()) {
            // Ephemeral: 32 random bytes, NEVER persisted. The CLI run's
            // QR is single-use by design (the invite TTL bounds it) so we
            // do not need to remember the key across runs.
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return FixedIrohSecretKeyStore(seed)
        }
        return FileIrohSecretKeyStore(path)
    }
}

/**
 * Internal `--qr-format` parser. Either the literal `text` or `png=<path>`.
 */
private sealed interface QrFormatSpec {
    data object Text : QrFormatSpec
    data class Png(val file: File) : QrFormatSpec
}

private fun parseQrFormat(raw: String): QrFormatSpec = when {
    raw == "text" -> QrFormatSpec.Text
    raw.startsWith("png=") -> QrFormatSpec.Png(File(raw.removePrefix("png=")))
    else -> error("--qr-format must be 'text' or 'png=<path>', got: $raw")
}

/**
 * Internal response parser. The admin router always returns
 * `{ "type": "admin_rpc_response", "success": true|false, "result": ... }`
 * on success or `... "error": "..."` on failure. We pull the `qr_invite`
 * field out of `result` for the renderer.
 */
private data class ParsedAdminResponse(
    val success: Boolean,
    val qrInvite: String?,
    val error: String?,
)

private fun parseAdminResponse(response: String): ParsedAdminResponse {
    val obj = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull()
        ?: return ParsedAdminResponse(success = false, qrInvite = null, error = "non-json response")
    val success = obj["success"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: false
    if (!success) {
        return ParsedAdminResponse(
            success = false,
            qrInvite = null,
            error = obj["error"]?.jsonPrimitive?.content,
        )
    }
    val result = obj["result"] as? JsonObject
        ?: return ParsedAdminResponse(success = true, qrInvite = null, error = "missing result")
    return ParsedAdminResponse(
        success = true,
        qrInvite = result["qr_invite"]?.jsonPrimitive?.content,
        error = null,
    )
}

/**
 * The CLI does not have an `IrohNodeEndpoint` to ask for its node id (it
 * stands alone from the server). The protocol's wire format still expects
 * a `node_id` in the QR, so we synthesise one deterministically from the
 * 32-byte signing key. SHA-256(node-id-byte-string) gives a stable
 * lowercase hex string that matches the visual format iroh uses for
 * EndpointIds (32 bytes hex-encoded), and is the same value the wrapper
 * will hand to `IrohNodeEndpoint` when it is later bound with the same
 * secret-key file.
 *
 * Not used in production (where the wrapper command reads the live
 * `IrohNodeEndpoint.nodeIdHex()`); kept here so the CLI command is
 * testable without spinning up an Iroh endpoint.
 */
internal object PairNodeIdFactory {
    fun fromSecretKeyStore(
        keyStore: com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore,
    ): String = runBlocking {
        val bytes = keyStore.loadOrCreate()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
