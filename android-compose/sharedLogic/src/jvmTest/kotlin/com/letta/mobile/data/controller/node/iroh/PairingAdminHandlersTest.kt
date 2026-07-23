package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Management RPC surface for paired devices (d6e8g.7): get, rename, and
 * capability re-scope on top of the d6e8g.5 invite/list/revoke methods. All run
 * behind the connection auth gate and require admin.full (see
 * IrohPeerCapabilitiesTest).
 */
class PairingAdminHandlersTest {
    private val nodeId = "a".repeat(64)

    private fun router(pairing: IrohPairingService): AdminRpcRouter =
        AdminRpcRouter().also { PairingAdminHandlers.register(it, pairing) }

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
}
