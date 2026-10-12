package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.iroh.FixedIrohSecretKeyStore
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class IrohPairingRedeemerTest {
    private val nodeId = "ab".repeat(32)
    private val key = ByteArray(32) { 3 }
    private val token = "invite:" + "9".repeat(64)

    private fun invite(dial: String?) = ScannedInvite(nodeId, token, expiresAtMs = Long.MAX_VALUE, dial = dial)

    private fun response(success: Boolean, error: String? = null, alreadyPaired: Boolean? = null) =
        AppServerInboundFrame.AuthResponse(requestId = "r", success = success, error = error, alreadyPaired = alreadyPaired)

    @Test
    fun dialAddressComesFromTheEnvelopeNodeIdAndRejectsAForeignHint() {
        assertEquals(nodeId, PairingDialAddress.resolve(nodeId, null))
        assertEquals("$nodeId@203.0.113.7:4501", PairingDialAddress.resolve(nodeId, "203.0.113.7:4501"))
        assertEquals("$nodeId@10.0.0.2:1", PairingDialAddress.resolve(nodeId, "$nodeId@10.0.0.2:1"))
        // A hint naming another node would dial an attacker's host: refused.
        assertNull(PairingDialAddress.resolve(nodeId, "${"cd".repeat(32)}@10.0.0.2:1"))
        assertNull(PairingDialAddress.resolve(nodeId, "garbage hint"))
    }

    @Test
    fun presentsTheInviteWithThePersistentIdentityAndMapsSuccess() = runTest {
        var seenKey: ByteArray? = null
        var seen: InviteAuthRequest? = null
        val redeemer = IrohPairingRedeemer(FixedIrohSecretKeyStore(key), { secretKey, request ->
            seenKey = secretKey
            seen = request
            response(success = true, alreadyPaired = true)
        })
        val outcome = redeemer.redeem(invite("h:1"), "pixel")
        assertEquals(RedeemOutcome.Paired(PairedHost(nodeId, "$nodeId@h:1"), alreadyPaired = true), outcome)
        assertContentEquals(key, seenKey)
        assertEquals("pixel", seen?.peerName)
        assertEquals(token, seen?.inviteToken)
        assertEquals(false, token in seen.toString())
    }

    @Test
    fun hostReasonsMapToTypedErrors() {
        val host = PairedHost(nodeId, nodeId)
        mapOf(
            "invite_expired" to PairingError.EXPIRED,
            "invite_invalid" to PairingError.INVITE_INVALID,
            "rate_limited" to PairingError.RATE_LIMITED,
            "pairing_required" to PairingError.DENIED,
        ).forEach { (reason, expected) ->
            assertEquals(RedeemOutcome.Failed(expected), IrohPairingRedeemer.outcomeOf(response(false, reason), host))
        }
    }

    @Test
    fun unreachableAndMismatchedHostsFailWithoutPairing() = runTest {
        val failing = IrohPairingRedeemer(FixedIrohSecretKeyStore(key), { _, _ -> error("no route") })
        assertEquals(RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE), failing.redeem(invite(null), "pixel"))

        val slow = IrohPairingRedeemer(FixedIrohSecretKeyStore(key), { _, _ -> delay(60_000); response(true) }, timeoutMs = 10)
        assertEquals(RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE), slow.redeem(invite(null), "pixel"))

        val neverCalled = IrohPairingRedeemer(FixedIrohSecretKeyStore(key), { _, _ -> error("must not dial") })
        assertEquals(
            RedeemOutcome.Failed(PairingError.HOST_MISMATCH),
            neverCalled.redeem(invite("${"cd".repeat(32)}@10.0.0.2:1"), "pixel"),
        )
    }
}
