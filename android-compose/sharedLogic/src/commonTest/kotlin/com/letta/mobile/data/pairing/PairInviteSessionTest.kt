package com.letta.mobile.data.pairing

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PairInviteSessionTest {
    private class FakeHost : AdminRpcInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val devices = mutableListOf<String>()
        var failWith: String? = null

        override suspend fun invoke(method: String, params: JsonObject): JsonElement? {
            calls += method to params
            failWith?.let { throw ModelControlException(it) }
            return when (method) {
                "pair.invite.create" -> buildJsonObject {
                    put("pair_link", "meridian://pair?qr=letta-qr-v1.x")
                    put("invite_id", "inv-1")
                    put("expires_at_ms", 99L)
                    put("agent_ids", params["agent_ids"] ?: kotlinx.serialization.json.JsonNull)
                }
                "pair.peer.list" -> buildJsonArray {
                    devices.forEach { id -> add(buildJsonObject { put("nodeId", id); put("name", "dev-$id"); put("pairedAtMs", 1L) }) }
                }
                "pair.invite.list" -> JsonArray(emptyList())
                "pair.peer.revoke" -> buildJsonObject { put("revoked", JsonPrimitive(devices.remove(params["node_id"]!!.jsonPrimitive.content))) }
                else -> null
            }
        }
    }

    private val host = FakeHost().apply { devices += "old" }
    private val encoded = mutableListOf<String>()

    private fun TestScope.session() = PairInviteSession(
        PairingAdminRepository(host),
        { content -> encoded += content; QrModules(1) { _, _ -> true } },
        TestScope(StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun mintRequestsAScopedChatInviteAndRendersThePairLink() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel", agentIds = setOf("agent-1")))
        advanceUntilIdle()
        val (method, params) = host.calls.first { it.first == "pair.invite.create" }
        assertEquals("pair.invite.create", method)
        assertEquals("chat", params["role"]!!.jsonPrimitive.content)
        assertEquals(listOf("agent-1"), params["agent_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("meridian://pair?qr=letta-qr-v1.x"), encoded)
        assertNotNull(session.state.value.qr)
        assertEquals(listOf("old"), session.state.value.devices.map { it.nodeId })
    }

    @Test
    fun aNewDeviceAfterMintIsReportedAcceptedAndTheQrIsRemoved() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        host.devices += "new"
        session.refresh()
        advanceUntilIdle()
        assertEquals("new", session.state.value.accepted?.nodeId)
        assertNull(session.state.value.invite)
        assertNull(session.state.value.qr)
    }

    @Test
    fun revokingADeviceRefreshesTheList() = runTest {
        val session = session()
        session.refresh()
        advanceUntilIdle()
        session.revokeDevice("old")
        advanceUntilIdle()
        assertEquals(emptyList(), session.state.value.devices)
    }

    @Test
    fun aNonAdminDeviceGetsAnExplanationNotARawError() = runTest {
        host.failWith = "forbidden"
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        assertEquals(PairInviteSession.NOT_ALLOWED, session.state.value.error)
        assertEquals(false, session.state.value.busy)
    }
}
