package com.letta.mobile.desktop.qr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.HmacPairQrSigner
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairedPeer
import com.letta.mobile.data.controller.node.iroh.PairedPeerStore
import com.letta.mobile.qr.QrCode
import com.letta.mobile.qr.QrRenderer
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-nonza (sixv8.3): state + actions for the desktop QR pairing
 * install surface.
 *
 * Mirrors the CLI path in `PairCommand.kt` (sixv8.2) but exposes the wire
 * value, rendered PNG file, and pairing-store snapshot as Compose state so
 * the install screen and the execution-location picker can drive themselves
 * off a single controller.
 *
 * Wire contract — see `reference/qr-pairing-protocol.md` §4 + §5 + §7. We
 * call `pair.invite.create` with `qr: true` and pull the additive
 * `qr_invite` field out of the response. The renderer is the ONLY thing
 * that differs across CLI/Desktop/Mobile per §10; the wire format is the
 * protocol's contract.
 */
internal class DesktopPairInviteController(
    private val scope: CoroutineScope,
    private val router: AdminRpcRouter,
    private val pairing: IrohPairingService,
    private val pairingStore: PairedPeerStore,
    /** File the PNG is rendered to. Parent directories are created on demand. */
    private val pngOutputFile: File,
    /** Suggested name passed to `pair.invite.create` for the next invite. */
    private val initialName: String = "paired-peer",
    /**
     * Dispatchers are injected so tests can drive [mint] on the test
     * scheduler. Hard-coded, they hop off the test's scheduler mid-flight,
     * so `advanceUntilIdle()` races the background work and assertions fire
     * against half-applied state. Same pattern as
     * `ChatTimelineObserver.projectionDispatcher`.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Loading state — `true` while a `pair.invite.create` call is in flight. */
    var loading by mutableStateOf(false)
        private set

    /** Last error from the mint path; `null` when the last call succeeded. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The `qr_invite` wire string from the last successful mint, or `null`. */
    var wireValue by mutableStateOf<String?>(null)
        private set

    /** Suggested peer name from the last response (`suggested_name` field). */
    var suggestedName by mutableStateOf<String?>(null)
        private set

    /** Absolute expiry timestamp from the last response (`expires_at_ms`). */
    var expiresAtMs by mutableStateOf<Long?>(null)
        private set

    /** PNG file path of the rendered QR; exists only after a successful mint. */
    var pngFile by mutableStateOf<File?>(null)
        private set

    /** Current peer snapshot from [pairingStore], updated by [refreshPeers]. */
    val peers = mutableStateListOf<PairedPeer>()

    /**
     * Selection for the execution-location picker. `null` means "local" (the
     * desktop itself). The picker lands a real selection in a follow-up bead
     * (letta-mobile-6ub2o); for now we only persist the choice so it
     * survives a recomposition.
     */
    var selectedExecutionLocation by mutableStateOf<String?>(null)
        private set

    init {
        refreshPeers()
    }

    /**
     * Mint a new QR-encoded invite. Calls `pair.invite.create` with `qr: true`,
     * then renders the returned `qr_invite` field to [pngOutputFile] via
     * [QrCode] + [QrRenderer] (the same primitives the CLI uses). Idempotent
     * within a launch — running while another mint is in flight is a no-op.
     */
    fun mint() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val response = withContext(ioDispatcher) {
                    router.dispatch(
                        AdminRpcInvocation(
                            requestId = "pair-desktop-${System.nanoTime()}",
                            method = "pair.invite.create",
                            params = buildJsonObject {
                                put("name", initialName)
                                put("qr", true)
                            },
                            context = AdminRpcRequestContext.Authenticated,
                        ),
                    )
                }
                val parsed = kotlinx.serialization.json.Json
                    .parseToJsonElement(response).jsonObject
                if (parsed["success"]?.jsonPrimitive?.boolean != true) {
                    val err = parsed["error"]?.jsonPrimitive?.content
                        ?: "pair.invite.create failed"
                    error = err
                    return@launch
                }
                val result = parsed["result"]?.jsonObject ?: run {
                    error = "pair.invite.create returned no result"
                    return@launch
                }
                val qrInvite = (result["qr_invite"] as? JsonPrimitive)?.content
                if (qrInvite.isNullOrBlank()) {
                    error = "pair.invite.create returned no qr_invite (signer misconfigured?)"
                    return@launch
                }
                // Validate the envelope's signature field is non-empty. An empty
                // signature is a misconfig (the protocol requires a real signer
                // wired up — see reference/qr-pairing-protocol.md §5.1).
                val decoded = PairQrEnvelope.decode(qrInvite)
                if (decoded == null) {
                    error = "qr_invite payload failed to decode (malformed?)"
                    return@launch
                }
                if (decoded.signature.isBlank()) {
                    error = "qr_invite has empty signature (signer misconfigured?)"
                    return@launch
                }
                wireValue = qrInvite
                suggestedName = (result["suggested_name"] as? JsonPrimitive)?.content
                expiresAtMs = (result["expires_at_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
                // Decode + render. The QR schema is `letta-qr-v1.<base64>` per
                // §7.1; we encode the full wire value (not just the body) so
                // the scanner reads the canonical prefix + envelope.
                val matrix = withContext(computeDispatcher) {
                    QrCode.encode(qrInvite)
                }
                val written = withContext(ioDispatcher) {
                    QrRenderer.writePng(matrix, pngOutputFile)
                }
                if (written <= 0) {
                    error = "PNG renderer wrote zero bytes"
                    return@launch
                }
                pngFile = pngOutputFile
            } catch (t: Throwable) {
                // Doctrine 55b: surface verbatim; do not swallow with runCatching.
                error = t.message ?: t::class.simpleName ?: "mint failed"
            } finally {
                loading = false
            }
        }
    }

    /**
     * Refresh [peers] from the pairing store. The store has no Flow surface
     * (plain [PairedPeerStore]), so the caller drives this from a coroutine.
     * Cheap (in-memory read); safe to call after every observed redemption.
     */
    fun refreshPeers() {
        val snapshot = pairingStore.list()
        peers.clear()
        peers.addAll(snapshot)
        // If a new peer has shown up and we have not yet picked an execution
        // location, default the picker to the first peer (regression test for
        // the auto-navigation acceptance criterion).
        if (selectedExecutionLocation == null && peers.isNotEmpty()) {
            selectedExecutionLocation = peers.first().nodeId
        }
    }

    /**
     * Drop the current invite (the [wireValue] + [pngFile]) so the screen
     * can be re-used for a fresh QR. Does NOT remove the suggested name —
     * the next [mint] uses the same [initialName].
     */
    fun clearInvite() {
        wireValue = null
        pngFile = null
        expiresAtMs = null
    }

    /**
     * Update the picker's selection. The full action is wired in
     * `letta-mobile-6ub2o`; here we just persist the choice so the
     * collapsed-state label can show it.
     */
    fun selectExecutionLocation(nodeIdOrNull: String?) {
        selectedExecutionLocation = nodeIdOrNull
    }

    companion object {
        /**
         * Build the canonical desktop controller from the same primitives the
         * CLI uses (doctrine 11 — extend, do not parallel). The signer is
         * HMAC-keyed by the stable desktop Iroh identity (d6e8g.4) so the
         * `node_id` in the QR is the same identity the wrapper binds when
         * the phone scans and dials in. [secretKeyStore] is the on-disk
         * encrypted store behind `DesktopIrohIdentity.loadOrCreate()`.
         *
         * Pairing-store integration lives in the caller — the install
         * surface reuses the same `FilePairedPeerStore` the wrapper uses,
         * so the redemption record lands in a place both can see.
         */
        fun fromIdentity(
            scope: CoroutineScope,
            secretKeyStore: IrohSecretKeyStore,
            pairingStore: PairedPeerStore,
            pngOutputFile: File,
            suggestedName: String = "paired-peer",
            qrNodeIdHex: String,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): DesktopPairInviteController {
            val signer: PairQrSigner = HmacPairQrSigner(secretKeyStore)
            val pairing = IrohPairingService(store = pairingStore)
            val router = AdminRpcRouter().also { r ->
                com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers.register(
                    r,
                    pairing,
                    signer,
                    qrNodeIdHex,
                )
            }
            return DesktopPairInviteController(
                scope = scope,
                router = router,
                pairing = pairing,
                pairingStore = pairingStore,
                pngOutputFile = pngOutputFile,
                initialName = suggestedName,
                ioDispatcher = ioDispatcher,
                computeDispatcher = computeDispatcher,
            )
        }

        /** Convenience for tests: deterministic signer + in-memory stores. */
        fun forTest(
            scope: CoroutineScope,
            pngOutputFile: File,
            signer: PairQrSigner,
            qrNodeIdHex: String,
            pairingStore: PairedPeerStore,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): DesktopPairInviteController {
            val pairing = IrohPairingService(store = pairingStore)
            val router = AdminRpcRouter().also { r ->
                com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers.register(
                    r,
                    pairing,
                    signer,
                    qrNodeIdHex,
                )
            }
            return DesktopPairInviteController(
                scope = scope,
                router = router,
                pairing = pairing,
                pairingStore = pairingStore,
                pngOutputFile = pngOutputFile,
                ioDispatcher = ioDispatcher,
                computeDispatcher = computeDispatcher,
            )
        }

        /** Wire scheme constant — `letta-qr-v1` per protocol §7.1. */
        const val WIRE_SCHEME: String = PairQrEnvelope.SCHEME
    }
}
