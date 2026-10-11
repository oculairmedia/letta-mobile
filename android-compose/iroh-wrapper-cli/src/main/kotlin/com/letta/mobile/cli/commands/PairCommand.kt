package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.FilePairedPeerStore
import com.letta.mobile.data.controller.node.iroh.FilePairingInviteStore
import com.letta.mobile.data.controller.node.iroh.HmacPairQrSigner
import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.data.controller.node.iroh.PairingQrConfig
import com.letta.mobile.qr.QrCode
import com.letta.mobile.qr.QrRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Host-side "scan to pair" (letta-mobile-gw0h1 / sixv8.2, made real by
 * letta-mobile-dpv7m.1): mint a one-time invite on THIS host and render it as
 * a scannable QR.
 *
 * The invite is written to the same pending-invite store the serving wrapper
 * redeems from (`--pairing-store-file` + `--pairing-invite-store-file`), so
 * the QR works against the running `app-server-serve-iroh` process without an
 * admin connection. The QR names the host's REAL NodeId (derived from
 * `--iroh-secret-key-file`) and encodes the scannable pairing link
 * (`meridian://pair?qr=…&dial=…`) — a phone camera opens the app straight
 * into pairing.
 *
 * The grant defaults to the least-privilege chat role; `--role` /
 * `--capabilities` / `--agents` widen or narrow it. The secret is shown only
 * as the QR (and with `--show-link`); it is never logged.
 *
 * Usage:
 *   ```
 *   meridian-iroh-wrapper pair --agents agent-123 --name "alice-phone"
 *   meridian-iroh-wrapper pair --qr-format png=/tmp/qr.png --dial <node-id>@203.0.113.7:4501
 *   ```
 */
class PairCommand : CliktCommand(name = "pair") {

    override fun help(context: Context): String = """
        Mint a one-time pairing invite on this host and show it as a QR.

        Scan it with the Meridian app (or a phone camera) to pair that device.
        Requires the same --iroh-secret-key-file and --pairing-store-file the
        running app-server-serve-iroh uses; the invite is redeemed by that
        server. Default grant: chat with every agent (or --agents), no admin.

        Output modes:
          --qr-format text              Unicode half-block QR to stdout (default).
          --qr-format png=<path>        PNG file at the given path (headless install).
    """.trimIndent()

    private val qrFormat by option(
        "--qr-format",
        help = "Output mode: `text` (default, Unicode QR to stdout) or `png=<path>`.",
    ).default("text").validate {
        require(it == "text" || it.startsWith("png=")) {
            "--qr-format must be 'text' or 'png=<path>', got: $it"
        }
    }

    private val name by option("--name", help = "Suggested device label (defaults to 'paired-peer').")

    private val ttlMs by option(
        "--ttl-ms",
        help = "Invite lifetime in ms (default 5 minutes, capped at 1 hour).",
    )

    private val role by option(
        "--role",
        help = "Grant for the paired device: chat (default) | desktop | vibesync | admin.",
    )

    private val capabilities by option(
        "--capabilities",
        help = "Explicit comma-separated capabilities (overrides --role), e.g. chat.read,chat.send.",
    )

    private val agents by option(
        "--agents",
        help = "Comma-separated agent ids the device may use (default: every agent).",
    )

    private val dial by option(
        "--dial",
        envvar = "LETTA_IROH_PUBLIC_DIAL",
        help = "Dial address put in the pairing link: the server's Iroh ticket or " +
            "<node-id>@host:port reachable from the phone. Default: NodeId only (discovery).",
    )

    private val irohSecretKeyFile by option(
        "--iroh-secret-key-file",
        envvar = "LETTA_IROH_SECRET_KEY_FILE",
        help = "The serving wrapper's 32-byte Iroh secret-key file (gives the QR its NodeId).",
    )

    private val pairingStoreFile by option(
        "--pairing-store-file",
        envvar = "LETTA_IROH_PAIRING_STORE",
        help = "The serving wrapper's paired-peer JSON store.",
    )

    private val pairingInviteStoreFile by option(
        "--pairing-invite-store-file",
        envvar = "LETTA_IROH_PAIRING_INVITE_STORE",
        help = "Pending-invite store (default: <pairing-store>.invites.json).",
    )

    private val showLink by option(
        "--show-link",
        help = "Also print the pairing link (contains the one-time secret) for copy/paste.",
    ).flag(default = false)

    private val quiet by option(
        "--quiet",
        help = "Suppress informational lines (PNG mode).",
    ).default("false")

    override fun run() = runBlocking {
        val keyPath = irohSecretKeyFile?.takeIf { it.isNotBlank() }
            ?: fail("--iroh-secret-key-file is required: the QR must name the serving host's NodeId")
        val storePath = pairingStoreFile?.takeIf { it.isNotBlank() }
            ?: fail("--pairing-store-file is required: the invite must land where the server redeems it")
        val keyStore = FileIrohSecretKeyStore(keyPath)
        val router = buildPairRouter(
            keyStore = keyStore,
            peerStore = Path.of(storePath),
            inviteStore = PairingStorePaths.inviteStore(Path.of(storePath), pairingInviteStoreFile),
            dial = dial,
        )
        val minted = mintInvite(router, inviteParams())
        render(minted)
    }

    private fun inviteParams(): JsonObject = buildJsonObject {
        name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        ttlMs?.toLongOrNull()?.let { put("ttl_ms", it) }
        role?.takeIf { it.isNotBlank() }?.let { put("role", it) }
        capabilities?.takeIf { it.isNotBlank() }?.let { put("capabilities", it) }
        agents?.takeIf { it.isNotBlank() }?.let { put("agent_ids", it) }
        put("qr", true)
    }

    private fun render(minted: MintedInvite) {
        val matrix = QrCode.encode(minted.pairLink)
        when (val mode = parseQrFormat(qrFormat)) {
            is QrFormatSpec.Text -> print(QrRenderer.renderText(matrix)).also { println() }
            is QrFormatSpec.Png -> {
                val bytes = QrRenderer.writePng(matrix, mode.file)
                if (quiet != "true") println("Wrote $bytes bytes to ${mode.file.absolutePath}")
            }
        }
        if (quiet != "true") {
            println("Invite ${minted.inviteId} for '${minted.name}' grants ${minted.capabilities}; expires at ${minted.expiresAtMs}.")
            println("Revoke before use: meridian peer invite-revoke --invite-id ${minted.inviteId}")
        }
        if (showLink) println("Pair link: ${minted.pairLink}")
    }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}

/** What the operator sees after a mint; the secret lives only inside [pairLink]. */
internal data class MintedInvite(
    val pairLink: String,
    val inviteId: String,
    val name: String,
    val capabilities: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String = "MintedInvite(id=$inviteId, <redacted>)"
}

/**
 * The host-side mint path, testable without Clikt: pairing service over the
 * SHARED file stores + the HMAC signer keyed by the host identity, naming the
 * host's real NodeId.
 */
internal fun buildPairRouter(
    keyStore: IrohSecretKeyStore,
    peerStore: Path,
    inviteStore: Path,
    dial: String?,
): AdminRpcRouter {
    val nodeId = PairNodeIdFactory.fromSecretKeyStore(keyStore)
    val pairing = IrohPairingService(FilePairedPeerStore(peerStore), FilePairingInviteStore(inviteStore))
    return AdminRpcRouter().also { router ->
        val qr = PairingQrConfig(HmacPairQrSigner(keyStore), nodeId) { dial?.takeIf { it.isNotBlank() } ?: nodeId }
        PairingAdminHandlers.register(router, pairing, qr)
    }
}

internal suspend fun mintInvite(router: AdminRpcRouter, params: JsonObject): MintedInvite {
    val response = router.dispatch(
        AdminRpcInvocation(
            requestId = "pair-cli-${System.nanoTime()}",
            method = "pair.invite.create",
            params = params,
            context = AdminRpcRequestContext.Authenticated,
        ),
    )
    val obj = Json.parseToJsonElement(response).jsonObject
    if (obj["success"]?.jsonPrimitive?.boolean != true) {
        error("pair.invite.create failed: ${obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}")
    }
    val result = obj["result"]?.jsonObject ?: error("pair.invite.create returned no result")
    fun field(key: String) = (result[key] as? JsonPrimitive)?.contentOrNull
    return MintedInvite(
        pairLink = field("pair_link") ?: error("pair.invite.create returned no pair_link (signer misconfigured?)"),
        inviteId = field("invite_id").orEmpty(),
        name = field("suggested_name").orEmpty(),
        capabilities = result["capabilities"].toString(),
        expiresAtMs = field("expires_at_ms")?.toLongOrNull() ?: 0L,
    )
}

/** Internal `--qr-format` parser. Either the literal `text` or `png=<path>`. */
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
 * The host's NodeId as the QR names it: the Ed25519 public key derived from
 * the identity secret — identical to `IrohNodeEndpoint.nodeIdHex()` for an
 * endpoint bound with the same key file (dpv7m.1; this used to be a SHA-256 of
 * the secret, which no endpoint ever answers to).
 */
internal object PairNodeIdFactory {
    fun fromSecretKeyStore(keyStore: IrohSecretKeyStore): String = runBlocking {
        IrohNodeIdentity.nodeIdHexFromSecretBytes(keyStore.loadOrCreate())
    }
}
