package com.letta.mobile.data.pairing.composition

import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.InviteAuthRequest
import com.letta.mobile.data.transport.iroh.InviteAuthenticator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** letta-mobile-wisqe S1b: the browser presents invites by NodeId through the relay, with its persistent key. */
class RelayPairingNetworkTest {
    private val nodeId = "ab".repeat(32)
    private val key = ByteArray(32) { 5 }
    private val token = "invite:" + "9".repeat(64)

    private fun invite(dial: String? = "203.0.113.7:4501") = ScannedInvite(nodeId, token, expiresAtMs = Long.MAX_VALUE, dial = dial)

    private fun response(success: Boolean, error: String? = null, alreadyPaired: Boolean? = null) =
        AppServerInboundFrame.AuthResponse(requestId = "r", success = success, error = error, alreadyPaired = alreadyPaired)

    private class RecordingAuthenticator(private val answer: suspend () -> AppServerInboundFrame.AuthResponse) : InviteAuthenticator {
        val keys = mutableListOf<ByteArray>()
        val requests = mutableListOf<InviteAuthRequest>()

        override suspend fun authenticate(secretKey: ByteArray, request: InviteAuthRequest): AppServerInboundFrame.AuthResponse {
            keys += secretKey
            requests += request
            return answer()
        }
    }

    private fun network(authenticator: InviteAuthenticator, timeoutMs: Long = RelayPairingNetwork.DEFAULT_TIMEOUT_MS) =
        RelayPairingNetwork(authenticator, nodeIds = { "node-" + it.first() }, timeoutMs = timeoutMs)

    @Test
    fun pairsByNodeIdAloneWithThePersistentKeyIgnoringTheHint() = runTest {
        val authenticator = RecordingAuthenticator { response(true) }
        val outcome = network(authenticator).redeemer { key }.redeem(invite(), "Browser")
        val paired = assertIs<RedeemOutcome.Paired>(outcome)
        assertEquals(PairedHost(nodeId, nodeId), paired.host)
        assertEquals("iroh://$nodeId", paired.host.serverUrl)
        assertEquals(false, paired.alreadyPaired)
        assertContentEquals(key, authenticator.keys.single())
        val request = authenticator.requests.single()
        // The relay dials the envelope's NodeId; the link's host:port hint is never used.
        assertEquals(nodeId, request.dialAddress)
        assertEquals(token, request.inviteToken)
        assertEquals("Browser", request.peerName)
    }

    @Test
    fun aDeviceThatWasAlreadyPairedReadsAsSo() = runTest {
        val authenticator = RecordingAuthenticator { response(true, alreadyPaired = true) }
        val outcome = network(authenticator).redeemer { key }.redeem(invite(null), "Browser")
        assertTrue(assertIs<RedeemOutcome.Paired>(outcome).alreadyPaired)
    }

    @Test
    fun mapsTheHostsRejectionLabels() = runTest {
        val expected = mapOf(
            "invite_expired" to PairingError.EXPIRED,
            "invite_invalid" to PairingError.INVITE_INVALID,
            "rate_limited" to PairingError.RATE_LIMITED,
            "peer_revoked" to PairingError.DENIED,
        )
        expected.forEach { (label, error) ->
            val outcome = network(RecordingAuthenticator { response(false, label) }).redeemer { key }.redeem(invite(), "Browser")
            assertEquals(RedeemOutcome.Failed(error), outcome, label)
        }
    }

    @Test
    fun anUnreachableOrSilentHostIsHostUnreachable() = runTest {
        val failing = network(RecordingAuthenticator { error("relay refused") }).redeemer { key }
        assertEquals(RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE), failing.redeem(invite(), "Browser"))
        val silent = network(RecordingAuthenticator { awaitCancellation() }, timeoutMs = 10).redeemer { key }
        assertEquals(RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE), silent.redeem(invite(), "Browser"))
    }

    @Test
    fun noPersistentIdentityFailsWithoutDialling() = runTest {
        val authenticator = RecordingAuthenticator { response(true) }
        val outcome = network(authenticator).redeemer { error("no persistent identity") }.redeem(invite(), "Browser")
        assertEquals(RedeemOutcome.Failed(PairingError.HOST_UNREACHABLE), outcome)
        assertTrue(authenticator.requests.isEmpty())
    }

    @Test
    fun cancellationIsNotAFailure() = runTest {
        val redeemer = network(RecordingAuthenticator { throw CancellationException("left") }).redeemer { key }
        assertIs<CancellationException>(runCatching { redeemer.redeem(invite(), "Browser") }.exceptionOrNull())
    }

    @Test
    fun nodeIdsComeFromTheBindingsDerivation() {
        assertEquals("node-5", network(RecordingAuthenticator { response(true) }).nodeIdOf(key))
    }
}
