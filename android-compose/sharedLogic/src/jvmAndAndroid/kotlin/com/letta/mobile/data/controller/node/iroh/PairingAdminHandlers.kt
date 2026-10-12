package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.pairing.PairLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Admin RPC surface for one-time pairing invitations (d6e8g.5). Registered
 * only when a pairing service is configured; all methods run behind the
 * connection's authentication gate and require admin.full
 * ([IrohPeerCapabilities.forAdminMethod]), so only an administrator can mint
 * invites or manage pairings. Invite secrets appear ONLY in the response to
 * the caller who minted them — never in telemetry or errors.
 *
 * letta-mobile-gw0h1 (sixv8.2): when the caller sets `qr: true`, the response
 * carries an additive `qr_invite` field (the `letta-qr-v1.<base64url>` wire
 * format from `reference/qr-pairing-protocol.md` §5.1 + §7.1) and, since
 * letta-mobile-dpv7m.1, a scannable `pair_link` that adds the host's dial
 * address. Existing fields (`invite`, `deep_link`, `expires_at_ms`,
 * `suggested_name`) are unchanged.
 *
 * dpv7m.1 invite scope: `role` (chat|desktop|vibesync|admin) or explicit
 * `capabilities` choose the grant (default chat — never admin unless asked),
 * `agent_ids` confines the redeeming device to those agents, and `ttl_ms` is
 * capped at [IrohPairingService.MAX_TTL_MS].
 */
object PairingAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true }

    /** Backward-compatible registration (no QR support). */
    fun register(router: AdminRpcRouter, pairing: IrohPairingService?) {
        register(router, pairing, qrConfig = null)
    }

    /** Legacy QR registration without a dial hint (desktop/Android in-process mint, tests). */
    fun register(router: AdminRpcRouter, pairing: IrohPairingService?, qrSigner: PairQrSigner, qrNodeIdHex: String?) {
        register(router, pairing, qrNodeIdHex?.let { PairingQrConfig(qrSigner, it) })
    }

    /**
     * Register with optional QR minting. [qrConfig] names the minting identity
     * (the host's Iroh NodeId — the public key the scanner dials and
     * authenticates), its signer, and the dial hint (ticket or
     * `<node>@host:port`). When the caller sets `qr: true` and it is wired, we
     * emit `qr_invite` and `pair_link`.
     *
     * Safety: a null config, a blank NodeId, or the no-op signer collapses to
     * the no-QR path so a misconfigured host never publishes an unsigned QR.
     */
    fun register(router: AdminRpcRouter, pairing: IrohPairingService?, qrConfig: PairingQrConfig?) {
        if (pairing == null) return
        val qr = QrMinting(qrConfig)
        router.register("pair.invite.create") { params -> createInvite(pairing, qr, params) }
        router.register("pair.invite.list") { _ -> json.encodeToJsonElement(pairing.listInvites().map(::inviteSummary)) }
        router.register("pair.invite.revoke") { params ->
            val id = params.requireString("invite_id")
            buildJsonObject { put("revoked", pairing.revokeInvite(InviteId(id))) }
        }
        registerPeerMethods(router, pairing)
    }

    private fun registerPeerMethods(router: AdminRpcRouter, pairing: IrohPairingService) {
        router.register("pair.peer.list") { _ ->
            json.encodeToJsonElement(pairing.listPeers())
        }
        router.register("pair.peer.get") { params ->
            val nodeId = params.requireString("node_id")
            buildJsonObject {
                put("peer", pairing.peer(nodeId)?.let(json::encodeToJsonElement) ?: JsonNull)
            }
        }
        router.register("pair.peer.rename") { params ->
            val nodeId = params.requireString("node_id")
            val name = params.requireString("name")
            val updated = pairing.rename(nodeId, name)
                ?: adminError("no paired peer for node_id")
            json.encodeToJsonElement(updated)
        }
        router.register("pair.peer.set_capabilities") { params ->
            val nodeId = params.requireString("node_id")
            val capabilities = readStringSet(params?.get("capabilities")) ?: adminError("capabilities is required")
            requireKnownCapabilities(capabilities)
            val updated = pairing.setCapabilities(nodeId, capabilities)
                ?: adminError("no paired peer for node_id")
            json.encodeToJsonElement(updated)
        }
        router.register("pair.peer.set_agents") { params ->
            val nodeId = params.requireString("node_id")
            // `agent_ids: null` (or absent) lifts the scope; an empty list is refused.
            val agents = readStringSet(params?.get("agent_ids"))
            if (agents != null && agents.isEmpty()) adminError("agent_ids must name at least one agent (or be null)")
            val updated = pairing.setAgentScope(nodeId, agents)
                ?: adminError("no paired peer for node_id")
            json.encodeToJsonElement(updated)
        }
        router.register("pair.peer.revoke") { params ->
            val nodeId = params.requireString("node_id")
            buildJsonObject { put("revoked", pairing.revoke(nodeId)) }
        }
    }

    private class QrMinting(private val config: PairingQrConfig?) {
        private val signer: PairQrSigner = config?.signer ?: NoOpPairQrSigner
        private val nodeId = config?.nodeIdHex?.takeIf { it.isNotBlank() && signer !is NoOpPairQrSigner }

        /** The `qr_invite` envelope + `pair_link`, or null when QR minting is not configured. */
        fun mint(signedSecret: String, expiresAtMs: Long): Pair<String, String>? {
            val id = nodeId ?: return null
            val signature = signer.sign(id, signedSecret, expiresAtMs).takeIf { it.isNotBlank() } ?: return null
            val envelope = PairQrEnvelope.encode(
                nodeIdHex = id,
                signedSecret = signedSecret,
                expiresAtMs = expiresAtMs,
                signature = signature,
            )
            val dial = runCatching { config?.dialHint?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }
            return envelope to PairLink(qrInvite = envelope, dial = dial).encode()
        }
    }

    private fun createInvite(pairing: IrohPairingService, qr: QrMinting, params: JsonObject?): JsonElement {
        val request = readInviteRequest(params)
        val wantQr = params?.get("qr")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        pairing.pruneExpired()
        val invite = pairing.createInvite(request)
        // The signed_secret carries the `invite:` prefix by the protocol's
        // choice (§5.1): it is exactly the auth token the redeemer presents.
        val signedSecret = IrohPairingService.INVITE_TOKEN_PREFIX + invite.secret
        val minted = if (wantQr) qr.mint(signedSecret, invite.expiresAtMs) else null
        return buildJsonObject {
            put("invite", signedSecret)
            put("invite_id", invite.id.value)
            put("deep_link", invite.deepLink())
            put("expires_at_ms", invite.expiresAtMs)
            put("suggested_name", invite.suggestedName)
            put("capabilities", stringArray(invite.capabilities))
            put("agent_ids", invite.agentIds?.let(::stringArray) ?: JsonNull)
            minted?.let { (envelope, link) ->
                put("qr_invite", envelope)
                put("pair_link", link)
            }
        }
    }

    private fun readInviteRequest(params: JsonObject?): IrohPairingService.InviteRequest {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: "paired-peer"
        val ttlMs = params?.get("ttl_ms")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: IrohPairingService.DEFAULT_TTL_MS
        val agents = readStringSet(params?.get("agent_ids"))
        if (agents != null && agents.isEmpty()) adminError("agent_ids must name at least one agent (omit it for every agent)")
        return IrohPairingService.InviteRequest(
            suggestedName = name,
            ttlMs = IrohPairingService.effectiveTtl(ttlMs),
            capabilities = readGrant(params),
            agentIds = agents,
        )
    }

    /** Explicit `capabilities` win over `role`; neither => least-privilege chat. */
    private fun readGrant(params: JsonObject?): Set<String> {
        readStringSet(params?.get("capabilities"))?.let { explicit ->
            if (explicit.isEmpty()) adminError("capabilities must not be empty")
            requireKnownCapabilities(explicit)
            return explicit
        }
        val role = params?.get("role")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return IrohPeerCapabilities.CHAT_ROLE
        return IrohPeerCapabilities.ROLES[role]
            ?: adminError("unknown role: $role (expected ${IrohPeerCapabilities.ROLES.keys.sorted().joinToString("|")})")
    }

    private fun requireKnownCapabilities(capabilities: Set<String>) {
        val unknown = capabilities - IrohPeerCapabilities.ALL
        if (unknown.isNotEmpty()) adminError("unknown capabilities: ${unknown.sorted().joinToString(",")}")
    }

    private fun inviteSummary(invite: PendingPairingInvite): JsonElement = buildJsonObject {
        put("invite_id", invite.id.value)
        put("suggested_name", invite.suggestedName)
        put("created_at_ms", invite.createdAtMs)
        put("expires_at_ms", invite.expiresAtMs)
        put("capabilities", stringArray(invite.capabilities))
        put("agent_ids", invite.agentIds?.let(::stringArray) ?: JsonNull)
    }

    private fun stringArray(values: Set<String>): JsonArray = buildJsonArray {
        values.sorted().forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject?.requireString(key: String): String =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: adminError("$key is required")

    /**
     * A JSON array (`["a", ...]`) or comma-separated string (`"a,b"`) so both
     * structured callers and CLI operators can drive the same method. Absent or
     * JSON null => null.
     */
    private fun readStringSet(element: JsonElement?): Set<String>? = when (element) {
        null, JsonNull -> null
        is JsonArray -> element.map { it.jsonPrimitive.content }
        else -> element.jsonPrimitive.contentOrNull.orEmpty().split(",")
    }?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

    val methods: Set<String> = setOf(
        "pair.invite.create",
        "pair.invite.list",
        "pair.invite.revoke",
        "pair.peer.list",
        "pair.peer.get",
        "pair.peer.rename",
        "pair.peer.set_capabilities",
        "pair.peer.set_agents",
        "pair.peer.revoke",
    )
}
