package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Management RPC surface for paired devices (d6e8g.7): get, rename, and
 * capability re-scope on top of the d6e8g.5 invite/list/revoke methods. All run
 * behind the connection auth gate and require admin.full (see
 * IrohPeerCapabilitiesTest).
 *
 * letta-mobile-gw0h1 (sixv8.2): the `pair.invite.create` extension test
 * pin is below (`qrTrueEmitsLettaQrV1Envelope` / `qrFalseOmitsQrInvite` /
 * `qrEnvelopeMatchesProtocol`). It exercises the protocol §5.1 / §7.1 wire
 * format end-to-end through the in-process admin router.
 */
class PairingAdminHandlersTest {
    private val nodeId = "a".repeat(64)

    private fun router(pairing: IrohPairingService): AdminRpcRouter =
        AdminRpcRouter().also { PairingAdminHandlers.register(it, pairing) }

    private fun router(
        pairing: IrohPairingService,
        signer: PairQrSigner,
        qrNodeIdHex: String?,
    ): AdminRpcRouter =
        AdminRpcRouter().also { PairingAdminHandlers.register(it, pairing, signer, qrNodeIdHex) }

    private fun pairedService(): IrohPairingService {
        val pairing = IrohPairingService(InMemoryPairedPeerStore())
        pairing.redeem(pairing.createInvite("desk").secret, nodeId)
        return pairing
    }

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, Any>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = buildJsonObject {
                    params.forEach { (k, v) ->
                        when (v) {
                            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
                            is Boolean -> put(k, v)
                            else -> put(k, JsonPrimitive(v.toString()))
                        }
                    }
                },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun managementMethodsRegistered() {
        assertEquals(
            setOf(
                "pair.invite.create", "pair.peer.list", "pair.peer.get",
                "pair.peer.rename", "pair.peer.set_capabilities", "pair.peer.revoke",
            ),
            PairingAdminHandlers.methods,
        )
        assertTrue(PairingAdminHandlers.methods.all { it in router(pairedService()).registeredMethods })
    }

    @Test
    fun getReturnsThePeerOrNull() = runTest {
        val r = router(pairedService())
        val found = dispatch(r, "pair.peer.get", mapOf("node_id" to nodeId))
        assertTrue(found.contains("\"success\":true") && found.contains(nodeId))
        val missing = dispatch(r, "pair.peer.get", mapOf("node_id" to "b".repeat(64)))
        assertTrue(missing.contains("\"success\":true") && missing.contains("\"peer\":null"))
    }

    @Test
    fun renameUpdatesLabelAndRejectsUnknownPeer() = runTest {
        val pairing = pairedService()
        val r = router(pairing)
        val ok = dispatch(r, "pair.peer.rename", mapOf("node_id" to nodeId, "name" to "renamed-desk"))
        assertTrue(ok.contains("\"success\":true") && ok.contains("renamed-desk"))
        assertEquals("renamed-desk", pairing.peer(nodeId)?.name)

        val ghost = dispatch(r, "pair.peer.rename", mapOf("node_id" to "b".repeat(64), "name" to "x"))
        assertTrue(ghost.contains("\"success\":false") && ghost.contains("no paired peer"))

        val blank = dispatch(r, "pair.peer.rename", mapOf("node_id" to nodeId, "name" to ""))
        assertTrue(blank.contains("\"success\":false") && blank.contains("name is required"))
    }

    @Test
    fun setCapabilitiesAcceptsArrayAndCsvAndRejectsUnknown() = runTest {
        val pairing = pairedService()
        val r = router(pairing)

        val arr = dispatch(r, "pair.peer.set_capabilities", mapOf("node_id" to nodeId, "capabilities" to listOf("chat.read", "admin.full")))
        assertTrue(arr.contains("\"success\":true"), arr)
        assertEquals(setOf("chat.read", "admin.full"), pairing.peer(nodeId)?.capabilities)

        val csv = dispatch(r, "pair.peer.set_capabilities", mapOf("node_id" to nodeId, "capabilities" to "chat.read, chat.send"))
        assertTrue(csv.contains("\"success\":true"), csv)
        assertEquals(setOf("chat.read", "chat.send"), pairing.peer(nodeId)?.capabilities)

        val bad = dispatch(r, "pair.peer.set_capabilities", mapOf("node_id" to nodeId, "capabilities" to listOf("chat.read", "not.a.cap")))
        assertTrue(bad.contains("\"success\":false") && bad.contains("unknown capabilities: not.a.cap"), bad)
        // The rejected write must not have mutated the peer.
        assertEquals(setOf("chat.read", "chat.send"), pairing.peer(nodeId)?.capabilities)
    }

    @Test
    fun mutatingMethodsRequireNodeId() = runTest {
        val r = router(pairedService())
        listOf("pair.peer.get", "pair.peer.rename", "pair.peer.set_capabilities", "pair.peer.revoke").forEach { m ->
            val resp = dispatch(r, m, emptyMap())
            assertTrue(resp.contains("\"success\":false") && resp.contains("node_id is required"), "$m: $resp")
        }
    }

    // -----------------------------------------------------------------
    // letta-mobile-gw0h1: pair.invite.create QR extension.
    // -----------------------------------------------------------------
    @Test
    fun qrFalseOmitsQrInvite() = runTest {
        val r = router(pairedService(), NoOpPairQrSigner, "deadbeef".repeat(8))
        val resp = dispatch(r, "pair.invite.create", mapOf("name" to "no-qr"))
        val parsed = parseSuccess(resp)
        assertTrue(parsed.contains("\"invite\":"), "must keep the legacy invite field: $parsed")
        assertTrue(!parsed.contains("\"qr_invite\""), "qr_invite must be omitted when qr is unset: $parsed")
    }

    @Test
    fun qrTrueEmitsLettaQrV1Envelope() = runTest {
        val fixedId = "abcd1234".repeat(8) // 64 hex
        val r = router(
            pairing = pairedService(),
            signer = FixedSigner("test-signature"),
            qrNodeIdHex = fixedId,
        )
        val resp = dispatch(r, "pair.invite.create", mapOf("name" to "with-qr", "qr" to true))
        val parsed = parseSuccess(resp)
        val qr = qrValueOf(parsed)
        assertNotNull(qr, "qr_invite must be present: $parsed")
        assertTrue(qr.startsWith("letta-qr-v1."), "must use the protocol §7.1 scheme: $qr")
    }

    @Test
    fun qrEnvelopeDecodesToCanonicalFields() = runTest {
        val fixedId = "abcd1234".repeat(8)
        val r = router(
            pairing = IrohPairingService(InMemoryPairedPeerStore()),
            signer = FixedSigner("sig-blob"),
            qrNodeIdHex = fixedId,
        )
        val resp = dispatch(r, "pair.invite.create", mapOf("name" to "decode", "qr" to true))
        val parsed = parseSuccess(resp)
        val qr = qrValueOf(parsed)!!
        val decoded = PairQrEnvelope.decode(qr)
        assertNotNull(decoded, "decoder must accept our own envelope: $qr")
        assertEquals(1, decoded.version)
        assertEquals(fixedId, decoded.nodeIdHex)
        assertTrue(decoded.signedSecret.startsWith("invite:"))
        assertEquals("sig-blob", decoded.signature)
        assertTrue(decoded.expiresAtMs > 0L)
    }

    @Test
    fun qrBackwardCompatibleDefaultOmitsField() = runTest {
        // Default register(...) overload (no signer) must continue to
        // honour the protocol §4 backward-compat guarantee: no `qr_invite`
        // unless the caller opts in.
        val r = router(pairedService())
        val resp = dispatch(r, "pair.invite.create", emptyMap())
        val parsed = parseSuccess(resp)
        assertTrue(parsed.contains("\"invite\":"))
        assertTrue(!parsed.contains("\"qr_invite\""))
    }

    @Test
    fun qrNullSignerDropsQrEvenWithFlag() = runTest {
        val r = router(pairedService(), NoOpPairQrSigner, qrNodeIdHex = null)
        val resp = dispatch(r, "pair.invite.create", mapOf("qr" to true))
        val parsed = parseSuccess(resp)
        // The handler's safety check: missing node id / signer collapses to
        // the no-signer path. Field is omitted entirely (not `null`).
        assertTrue(!parsed.contains("\"qr_invite\""), parsed)
    }

    private fun parseSuccess(response: String): String {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(response).jsonObject
        assertTrue(obj["success"]?.jsonPrimitive?.boolean == true, "expected success: $response")
        return obj["result"]?.toString() ?: error("missing result: $response")
    }

    private fun qrValueOf(resultJson: String): String? {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject
        val el = obj["qr_invite"] ?: return null
        return (el as? JsonPrimitive)?.content
    }

    /** Deterministic signer for test isolation — no key store / no randomness. */
    private class FixedSigner(private val blob: String) : PairQrSigner {
        override fun sign(nodeIdHex: String, signedSecret: String, expiresAtMs: Long): String = blob
    }
}

