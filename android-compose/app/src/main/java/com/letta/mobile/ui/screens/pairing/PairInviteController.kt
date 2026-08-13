package com.letta.mobile.ui.screens.pairing

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.HmacPairQrSigner
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.PairQrEnvelope
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairedPeerStore
import com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers
import com.letta.mobile.qr.QrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Immutable
data class PairInviteUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val wireValue: String? = null,
    val matrix: QrCode.Matrix? = null,
    val suggestedName: String? = null,
    val expiresAtMs: Long? = null,
)

/**
 * Server-mode invite-generation state machine (letta-mobile-g2d2i).
 *
 * Mirrors `desktop/src/main/kotlin/.../qr/DesktopPairInviteController.kt`
 * (nonza): call `pair.invite.create` with `qr: true` in-process against a
 * locally built [AdminRpcRouter], pull `qr_invite` out of the response, and
 * rasterize it with [QrCode.encode] — Compose Canvas draws the [QrCode.Matrix]
 * directly (via [QrMatrixCanvas]) instead of the desktop's PNG-file +
 * `java.awt`/`ImageIO` path (`QrRenderer`), which is JDK-only and unusable
 * on Android.
 *
 * A plain (non-Hilt) class so it's directly unit-testable, exactly like the
 * desktop controller's own test does — [PairInviteViewModel] is a thin Hilt
 * wrapper that constructs one via [fromIdentity] and republishes [uiState].
 */
class PairInviteController(
    private val scope: CoroutineScope,
    private val router: AdminRpcRouter,
    private val initialName: String = "paired-peer",
) {
    private val _uiState = MutableStateFlow(PairInviteUiState())
    val uiState: StateFlow<PairInviteUiState> = _uiState.asStateFlow()

    /**
     * Mint a new QR-encoded invite. Idempotent within a launch — calling
     * while a mint is already in flight is a no-op.
     */
    fun mint() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    router.dispatch(
                        AdminRpcInvocation(
                            requestId = "pair-mobile-${System.nanoTime()}",
                            method = "pair.invite.create",
                            params = buildJsonObject {
                                put("name", initialName)
                                put("qr", true)
                            },
                            context = AdminRpcRequestContext.Authenticated,
                        ),
                    )
                }
                val parsed = Json.parseToJsonElement(response).jsonObject
                if (parsed["success"]?.jsonPrimitive?.boolean != true) {
                    fail(parsed["error"]?.jsonPrimitive?.content ?: "pair.invite.create failed")
                    return@launch
                }
                val result = parsed["result"]?.jsonObject ?: run {
                    fail("pair.invite.create returned no result")
                    return@launch
                }
                val qrInvite = (result["qr_invite"] as? JsonPrimitive)?.content
                if (qrInvite.isNullOrBlank()) {
                    fail("pair.invite.create returned no qr_invite (signer misconfigured?)")
                    return@launch
                }
                // Structural sanity check only — see the trust-model doc
                // comment in PairingQrDecoder for why we do NOT (and cannot)
                // verify the signature here either; an empty signature just
                // means the signer wasn't wired up correctly.
                val decoded = PairQrEnvelope.decode(qrInvite)
                if (decoded == null) {
                    fail("qr_invite payload failed to decode (malformed?)")
                    return@launch
                }
                if (decoded.signature.isBlank()) {
                    fail("qr_invite has empty signature (signer misconfigured?)")
                    return@launch
                }
                val matrix = withContext(Dispatchers.Default) { QrCode.encode(qrInvite) }
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        wireValue = qrInvite,
                        matrix = matrix,
                        suggestedName = (result["suggested_name"] as? JsonPrimitive)?.content,
                        expiresAtMs = (result["expires_at_ms"] as? JsonPrimitive)?.content?.toLongOrNull(),
                    )
                }
            } catch (t: Throwable) {
                fail(t.message ?: t::class.simpleName ?: "mint failed")
            }
        }
    }

    /** Drop the current invite so the screen can show a fresh spinner before [mint] repopulates it. */
    fun clearInvite() {
        _uiState.update { it.copy(wireValue = null, matrix = null, expiresAtMs = null) }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(loading = false, error = message) }
    }

    companion object {
        /**
         * Build the controller from a real device identity: an HMAC signer
         * keyed by [secretKeyStore] (the same store instance that ultimately
         * seeds the listening Iroh endpoint's secret key, so `node_id` in the
         * QR matches whatever the phone actually dials in as), a pairing
         * service backed by [pairingStore], and [nodeIdHex] — the node id
         * hex derived from that same secret via
         * [com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity].
         */
        fun fromIdentity(
            scope: CoroutineScope,
            secretKeyStore: IrohSecretKeyStore,
            pairingStore: PairedPeerStore,
            nodeIdHex: String,
            suggestedName: String = "paired-peer",
        ): PairInviteController {
            val signer: PairQrSigner = HmacPairQrSigner(secretKeyStore)
            return forTest(scope, signer, nodeIdHex, pairingStore, suggestedName)
        }

        /** Convenience for tests: any signer + deterministic node id + in-memory or fake store. */
        fun forTest(
            scope: CoroutineScope,
            signer: PairQrSigner,
            nodeIdHex: String,
            pairingStore: PairedPeerStore,
            suggestedName: String = "paired-peer",
        ): PairInviteController {
            val pairing = IrohPairingService(store = pairingStore)
            val router = AdminRpcRouter().also { r ->
                PairingAdminHandlers.register(r, pairing, signer, nodeIdHex)
            }
            return PairInviteController(scope = scope, router = router, initialName = suggestedName)
        }
    }
}
