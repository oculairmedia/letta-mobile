package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.secrets.request.SecretRequestId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bootstrap session (a bearer-token holder, or an allowlisted peer) holds `admin.full` and can
 * mint an admin pairing invite. Redeeming that invite on the same connection must not make the
 * connection a paired device, or the session could answer its own secret request. Driven through
 * the real node connection's control-frame handling and request context, and the real router.
 */
class SecretRequestBootstrapPairingTest {
    private val pairing = IrohPairingService(InMemoryPairedPeerStore())

    private fun adminInvite(): String = IrohPairingService.INVITE_TOKEN_PREFIX +
        pairing.createInvite(IrohPairingService.InviteRequest("self", capabilities = setOf(IrohPeerCapabilities.ADMIN_FULL))).secret

    private suspend fun IrohNodeConnection.auth(requestId: String, token: String): JsonObject =
        Json.parseToJsonElement(
            checkNotNull(
                controlFrame(
                    buildJsonObject {
                        put("type", "auth")
                        put("request_id", requestId)
                        put("token", token)
                    }.toString(),
                ),
            ),
        ).jsonObject

    /** Every human route answers `forbidden` to [context] and nothing is written. */
    private suspend fun assertEveryHumanRouteRefuses(harness: SecretRouteHarness, id: SecretRequestId, context: AdminRpcRequestContext) {
        for (route in SecretRequestRoute.entries.filter { it.side == SecretRouteSide.HUMAN }) {
            val params = when (route) {
                SecretRequestRoute.LIST -> null
                SecretRequestRoute.SUBMIT -> submitParams(id, TestSecret("self-answered"))
                else -> idParams(id)
            }
            assertEquals(refusalEnvelope("forbidden"), harness.dispatch(route, params, context), route.method)
        }
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun aBearerSessionThatRedeemsItsOwnAdminInviteOnTheSameConnectionCannotAnswer() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val connection = controlConnection(harness, IrohAuthPolicy.BearerToken(TOKEN), pairing)

        assertEquals(JsonPrimitive(true), connection.auth("a1", TOKEN)["success"], "the bootstrap session authenticates")
        val redeemed = connection.auth("a2", adminInvite())

        assertEquals(JsonPrimitive(true), redeemed["success"], "the second auth is accepted and pairs the NodeId")
        assertEquals(JsonPrimitive(true), redeemed["paired"])
        val context = connection.requestContext()
        assertTrue(context.authenticated && IrohPeerCapabilities.ADMIN_FULL in context.grantedCapabilities)
        assertFalse(context.pairedDevice, "a connection that began as a bootstrap session never becomes a paired device")
        assertEveryHumanRouteRefuses(harness, id, context)
    }

    @Test
    fun anAllowlistedPeerThatRedeemsAnAdminInviteOnTheSameConnectionCannotAnswer() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val connection = controlConnection(harness, IrohAuthPolicy.PeerAllowlist(setOf(HUMAN_ENDPOINT)), pairing)

        assertEquals(JsonPrimitive(true), connection.auth("a1", adminInvite())["paired"])

        val context = connection.requestContext()
        assertFalse(context.pairedDevice)
        assertEveryHumanRouteRefuses(harness, id, context)
    }

    @Test
    fun aGenuinelyPairedDeviceOnAPairedPeersOnlyHostStillAnswers() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val pairingConnection = controlConnection(harness, IrohAuthPolicy.PairedPeersOnly, pairing)
        assertEquals(JsonPrimitive(true), pairingConnection.auth("a1", adminInvite())["paired"])
        assertTrue(pairingConnection.requestContext().pairedDevice, "an invite redeemed by a fresh session pairs the device")

        val device = controlConnection(harness, IrohAuthPolicy.PairedPeersOnly, pairing).requestContext()

        assertTrue(device.pairedDevice)
        val answered = resultOf(harness.dispatch(SecretRequestRoute.SUBMIT, submitParams(id, TestSecret("device-answer")), device))
        assertEquals("saved", answered?.getValue("receipt")?.jsonObject?.getValue("status")?.let { (it as JsonPrimitive).content })
    }

    /**
     * Not closed here, and pinned so it stays visible: the pairing record a bootstrap session
     * minted and redeemed outlives its connection, so a second, fresh connection from that NodeId is
     * a paired device. A bearer-token host registers no secret routes, but the paired-peer store
     * persists, so if the host later runs paired-peers-only that device can answer. Holding the
     * admin token lets you pair a device; whether that should change is an owner decision.
     */
    @Test
    fun aFreshConnectionAfterTheRedeemIsAPairedDeviceWhichIsAnOpenOwnerDecision() = runTest {
        val harness = SecretRouteHarness()
        val bootstrap = controlConnection(harness, IrohAuthPolicy.BearerToken(TOKEN), pairing)
        bootstrap.auth("a1", TOKEN)
        bootstrap.auth("a2", adminInvite())

        val fresh = controlConnection(harness, IrohAuthPolicy.PairedPeersOnly, pairing).requestContext()

        assertTrue(fresh.pairedDevice)
    }

    private companion object {
        val TOKEN = "b".repeat(64)
    }
}
