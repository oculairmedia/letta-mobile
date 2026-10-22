package com.letta.mobile.data.pairing

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** letta-mobile-wisqe S1b: every platform reads the host's answer to an invite the same way. */
class PairingAuthOutcomeTest {
    private val host = PairedHost(nodeId = "ab".repeat(32), dialAddress = "ab".repeat(32))
    private val invite = ScannedInvite(nodeId = host.nodeId, inviteToken = "invite:secret", expiresAtMs = 100_000L, dial = null)

    private fun response(success: Boolean, error: String? = null, alreadyPaired: Boolean? = null) =
        AppServerInboundFrame.AuthResponse(requestId = "r", success = success, error = error, alreadyPaired = alreadyPaired)

    @Test
    fun successPairsTheHost() {
        val paired = assertIs<RedeemOutcome.Paired>(PairingAuthOutcome.of(response(success = true), host))

        assertSame(host, paired.host)
        assertEquals(false, paired.alreadyPaired)
    }

    @Test
    fun alreadyPairedIsCarriedThrough() {
        val paired = assertIs<RedeemOutcome.Paired>(PairingAuthOutcome.of(response(success = true, alreadyPaired = true), host))

        assertEquals(true, paired.alreadyPaired)
    }

    @Test
    fun reasonLabelsMapToPairingErrors() {
        assertEquals(PairingError.EXPIRED, failure("invite_expired"))
        assertEquals(PairingError.INVITE_INVALID, failure("invite_invalid"))
        assertEquals(PairingError.RATE_LIMITED, failure("rate_limited"))
        assertEquals(PairingError.DENIED, failure("not_allowed"))
        assertEquals(PairingError.DENIED, failure(null))
    }

    @Test
    fun aPresentedInviteReadsTheHostAnswer() = runTest {
        var dials = 0

        val outcome = PairingAuthOutcome.present(invite, host, timeoutMs = 5_000L) {
            dials++
            response(success = false, error = "invite_expired")
        }

        assertEquals(PairingError.EXPIRED, assertIs<RedeemOutcome.Failed>(outcome).error)
        assertEquals(1, dials)
    }

    @Test
    fun aFailedDialIsAnUnreachableHost() = runTest {
        val outcome = PairingAuthOutcome.present(invite, host, timeoutMs = 5_000L) { error("relay down") }

        assertEquals(PairingError.HOST_UNREACHABLE, assertIs<RedeemOutcome.Failed>(outcome).error)
    }

    @Test
    fun aHostThatNeverAnswersIsUnreachable() = runTest {
        val outcome = PairingAuthOutcome.present(invite, host, timeoutMs = 5_000L) { awaitCancellation() }

        assertEquals(PairingError.HOST_UNREACHABLE, assertIs<RedeemOutcome.Failed>(outcome).error)
    }

    private fun failure(reason: String?): PairingError =
        assertIs<RedeemOutcome.Failed>(PairingAuthOutcome.of(response(success = false, error = reason), host)).error
}
