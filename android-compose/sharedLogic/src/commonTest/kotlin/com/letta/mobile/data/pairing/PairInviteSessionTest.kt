package com.letta.mobile.data.pairing

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class PairInviteSessionTest {
    private class FakeHost(private val clock: () -> Long) : AdminRpcInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val devices = mutableListOf<String>()
        var failWith: String? = null
        var minted = 0

        /** When set, pair.peer.revoke suspends until completed (the host has not answered yet). */
        var revokeGate: CompletableDeferred<Boolean>? = null

        override suspend fun invoke(method: String, params: JsonObject): JsonElement? {
            calls += method to params
            failWith?.let { throw ModelControlException(it) }
            return when (method) {
                "pair.invite.create" -> mintResult(params)
                "pair.peer.list" -> buildJsonArray {
                    devices.forEach { id -> add(buildJsonObject { put("nodeId", id); put("name", "dev-$id"); put("pairedAtMs", 1L) }) }
                }
                "pair.invite.list" -> JsonArray(emptyList())
                "pair.invite.revoke" -> buildJsonObject { put("revoked", true) }
                "pair.peer.revoke" -> {
                    val confirmed = revokeGate?.await() ?: true
                    val removed = confirmed && devices.remove(params["node_id"]!!.jsonPrimitive.content)
                    buildJsonObject { put("revoked", JsonPrimitive(removed)) }
                }
                else -> null
            }
        }

        private fun mintResult(params: JsonObject) = buildJsonObject {
            minted++
            put("pair_link", "meridian://pair?qr=letta-qr-v1.x$minted")
            put("invite_id", "inv-$minted")
            put("expires_at_ms", clock() + params["ttl_ms"]!!.jsonPrimitive.long)
            put("agent_ids", params["agent_ids"] ?: JsonNull)
        }
    }

    private var now = 10_000L
    private val host = FakeHost { now }.apply { devices += "old" }
    private val encoded = mutableListOf<String>()

    private fun TestScope.session() = PairInviteSession(
        PairingAdminRepository(host),
        { content -> encoded += content; QrModules(1) { _, _ -> true } },
        TestScope(StandardTestDispatcher(testScheduler)),
        clock = { now },
    )

    @Test
    fun mintRequestsAScopedLeastPrivilegeFiveMinuteInvite() = runTest {
        val session = session()
        assertEquals(InvitePhase.Configuring, session.phase())
        session.mint(PairInviteRequest("pixel", agentIds = setOf("agent-1")))
        advanceUntilIdle()
        val (_, params) = host.calls.first { it.first == "pair.invite.create" }
        assertEquals("chat", params["role"]!!.jsonPrimitive.content)
        assertEquals(5 * 60 * 1000L, params["ttl_ms"]!!.jsonPrimitive.long)
        assertEquals(listOf("agent-1"), params["agent_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("meridian://pair?qr=letta-qr-v1.x1"), encoded)
        assertNotNull(session.state.value.qr)
        assertEquals(listOf("old"), session.state.value.devices.map { it.nodeId })
    }

    @Test
    fun allowSettingsChangesIsTheOnlyWayToTheWiderRole() {
        assertEquals(PairInviteRequest.ROLE_CHAT, PairInviteRequest.roleFor(allowSettingsChanges = false))
        assertEquals(PairInviteRequest.ROLE_DESKTOP, PairInviteRequest.roleFor(allowSettingsChanges = true))
        assertEquals(PairInviteRequest.ROLE_CHAT, PairInviteRequest("x").role)
    }

    @Test
    fun theCardMovesShowingThenExpiringThenExpiredOnTheClock() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        assertEquals(5 * 60 * 1000L, assertIs<InvitePhase.Showing>(session.phase()).remainingMs)
        now += 5 * 60 * 1000L - InvitePhases.EXPIRING_WINDOW_MS
        assertIs<InvitePhase.Expiring>(session.phase())
        now += InvitePhases.EXPIRING_WINDOW_MS
        assertIs<InvitePhase.Expired>(session.phase())
        assertEquals(1, host.minted, "no auto-refresh: an expired code stays expired")
    }

    @Test
    fun newCodeAfterExpiryMintsAgainWithTheSameScope() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel", agentIds = setOf("a")))
        advanceUntilIdle()
        now += 10 * 60 * 1000L
        session.newCode()
        advanceUntilIdle()
        assertEquals(2, host.minted)
        assertEquals("inv-2", assertIs<InvitePhase.Showing>(session.phase()).invite.inviteId)
        val creates = host.calls.filter { it.first == "pair.invite.create" }.map { it.second["agent_ids"] }
        assertEquals(creates[0], creates[1])
        assertTrue(host.calls.none { it.first == "pair.invite.revoke" }, "an expired code needs no cancel")
    }

    @Test
    fun newCodeWhileTheOldOneIsLiveWithdrawsItFirst() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        session.newCode()
        advanceUntilIdle()
        val methods = host.calls.map { it.first }
        assertTrue(methods.indexOf("pair.invite.revoke") < methods.lastIndexOf("pair.invite.create"))
    }

    @Test
    fun aNewDeviceAfterMintIsRedeemedAndTheQrIsRemoved() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        host.devices += "new"
        session.refresh()
        advanceUntilIdle()
        assertEquals("new", assertIs<InvitePhase.Redeemed>(session.phase()).device.nodeId)
        assertNull(session.state.value.invite)
        assertNull(session.state.value.qr)
    }

    @Test
    fun cancellingTheOnScreenCodeShowsCancelled() = runTest {
        val session = session()
        session.mint(PairInviteRequest("pixel"))
        advanceUntilIdle()
        session.revokeInvite("inv-1")
        advanceUntilIdle()
        assertEquals(InvitePhase.Cancelled, session.phase())
        session.dismissInvite()
        assertEquals(InvitePhase.Configuring, session.phase())
    }

    @Test
    fun aRevokedDeviceReadsRevokingUntilTheHostConfirms() = runTest {
        val session = session()
        session.refresh()
        advanceUntilIdle()
        val gate = CompletableDeferred<Boolean>().also { host.revokeGate = it }
        session.revokeDevice("old")
        runCurrent()
        assertEquals(setOf("old"), session.state.value.revoking)
        assertEquals(listOf("old"), session.state.value.devices.map { it.nodeId }, "never removed on optimism alone")
        gate.complete(true)
        advanceUntilIdle()
        assertEquals(emptySet(), session.state.value.revoking)
        assertEquals(emptyList(), session.state.value.devices)
    }

    @Test
    fun anUnconfirmedRevokeKeepsTheDeviceAndSaysSo() = runTest {
        val session = session()
        session.refresh()
        advanceUntilIdle()
        host.revokeGate = CompletableDeferred(false)
        session.revokeDevice("old")
        advanceUntilIdle()
        assertEquals(PairInviteSession.REVOKE_FAILED, session.state.value.error)
        assertEquals(listOf("old"), session.state.value.devices.map { it.nodeId })
    }

    @Test
    fun revokeAllOthersSparesThisDevice() = runTest {
        host.devices += listOf("self", "b")
        val session = session()
        session.identifySelf("SELF")
        session.refresh()
        advanceUntilIdle()
        assertEquals("self", session.state.value.groups.thisDevice?.nodeId)
        session.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(listOf("self"), session.state.value.devices.map { it.nodeId })
    }

    @Test
    fun revokeAllOthersDoesNothingWhenThisDeviceIsUnknown() = runTest {
        host.devices += "b"
        val session = session()
        session.refresh()
        advanceUntilIdle()
        session.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(2, session.state.value.devices.size)
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
