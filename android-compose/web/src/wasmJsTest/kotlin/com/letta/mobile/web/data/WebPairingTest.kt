package com.letta.mobile.web.data

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.RelayInviteAuthenticator
import com.letta.mobile.data.pairing.RelayPairingRedeemer
import com.letta.mobile.data.pairing.ScannedInvite
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-wisqe S1b: the browser presents an invite to its host and keeps the host it paired with. */
@OptIn(ExperimentalEncodingApi::class)
class WebPairingTest {
    private val nodeId = "ab".repeat(30) + "cdef"
    private val secret = "invite:" + "5".repeat(64)

    private class Dial(val address: IrohServerAddress, val inviteToken: String, val peerName: String)

    private class RecordingAuthenticator(
        private val answer: suspend () -> AppServerInboundFrame.AuthResponse,
    ) : RelayInviteAuthenticator {
        val dials = mutableListOf<Dial>()

        override suspend fun authenticate(
            address: IrohServerAddress,
            inviteToken: String,
            peerName: String,
        ): AppServerInboundFrame.AuthResponse {
            dials += Dial(address, inviteToken, peerName)
            return answer()
        }
    }

    private fun invite(node: String = nodeId, dial: String? = "$nodeId@10.0.0.5:7777") =
        ScannedInvite(nodeId = node, inviteToken = secret, expiresAtMs = 100_000L, dial = dial)

    private fun answering(success: Boolean, error: String? = null, alreadyPaired: Boolean? = null) = RecordingAuthenticator {
        AppServerInboundFrame.AuthResponse(requestId = "r", success = success, error = error, alreadyPaired = alreadyPaired)
    }

    private fun pairLink(): String {
        val json = """{"version":1,"node_id":"$nodeId","signed_secret":"$secret","expires_at_ms":100000,"signature":"s"}"""
        return "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
    }

    @Test
    fun anAcceptedInviteDialsOnlyTheEnvelopeNodeIdAndPairsAsAnIrohBackend() = runTest {
        val authenticator = answering(success = true)

        val outcome = RelayPairingRedeemer(authenticator).redeem(invite(), "Browser")

        val paired = assertIs<RedeemOutcome.Paired>(outcome)
        assertEquals("iroh://$nodeId", paired.host.serverUrl)
        assertEquals(false, paired.alreadyPaired)
        val dial = authenticator.dials.single()
        assertEquals(IrohServerAddress(nodeId), dial.address)
        assertEquals(secret, dial.inviteToken)
        assertEquals("Browser", dial.peerName)
    }

    @Test
    fun anAlreadyPairedBrowserStillPairs() = runTest {
        val outcome = RelayPairingRedeemer(answering(success = true, alreadyPaired = true)).redeem(invite(), "Browser")

        assertEquals(true, assertIs<RedeemOutcome.Paired>(outcome).alreadyPaired)
    }

    @Test
    fun theHostReasonLabelsMapToPairingErrors() = runTest {
        val expected = mapOf(
            "invite_expired" to PairingError.EXPIRED,
            "invite_invalid" to PairingError.INVITE_INVALID,
            "rate_limited" to PairingError.RATE_LIMITED,
            "something_else" to PairingError.DENIED,
        )
        for ((reason, error) in expected) {
            val authenticator = answering(success = false, error = reason)

            val outcome = RelayPairingRedeemer(authenticator).redeem(invite(), "Browser")

            assertEquals(error, assertIs<RedeemOutcome.Failed>(outcome).error, reason)
            assertEquals(1, authenticator.dials.size, reason)
        }
    }

    @Test
    fun aFailedDialIsAnUnreachableHost() = runTest {
        val authenticator = RecordingAuthenticator { error("Could not connect over Iroh: relay down") }

        val outcome = RelayPairingRedeemer(authenticator).redeem(invite(), "Browser")

        assertEquals(PairingError.HOST_UNREACHABLE, assertIs<RedeemOutcome.Failed>(outcome).error)
        assertEquals(1, authenticator.dials.size)
    }

    @Test
    fun aHostThatNeverAnswersTimesOutAsUnreachable() = runTest {
        val authenticator = RecordingAuthenticator { awaitCancellation() }

        val outcome = RelayPairingRedeemer(authenticator, timeoutMs = 5_000L).redeem(invite(), "Browser")

        assertEquals(PairingError.HOST_UNREACHABLE, assertIs<RedeemOutcome.Failed>(outcome).error)
        assertEquals(1, authenticator.dials.size)
    }

    @Test
    fun anUnreadableNodeIdIsMalformedAndNeverDials() = runTest {
        val authenticator = answering(success = true)

        val outcome = RelayPairingRedeemer(authenticator).redeem(invite(node = "not-a-node-id", dial = null), "Browser")

        assertEquals(PairingError.MALFORMED, assertIs<RedeemOutcome.Failed>(outcome).error)
        assertEquals(0, authenticator.dials.size)
    }

    @Test
    fun thePairedHostIsTheBackendWithoutATokenAndSurvivesAReload() = runTest {
        val settings = InMemorySecureSettingsStore()
        val config = mutableStateOf(
            LettaConfig(id = "default", mode = LettaConfig.Mode.LOCAL, serverUrl = "wss://old.test/ws", accessToken = "old-secret"),
        )

        WebPairedHostStore(config, WebSettingsStore(settings)).saveAndActivate(PairedHost(nodeId, nodeId))

        assertEquals("iroh://$nodeId", config.value.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, config.value.mode)
        assertNull(config.value.accessToken)
        val reloaded = WebSettingsStore(settings).load()
        assertEquals("iroh://$nodeId", reloaded.serverUrl)
        assertEquals(LettaConfig.Mode.SELF_HOSTED, reloaded.mode)
        assertNull(reloaded.accessToken)
    }

    @Test
    fun aPastedLinkPairsThroughTheSharedFlowAndBecomesTheBackend() = runTest {
        val settings = InMemorySecureSettingsStore()
        val config = mutableStateOf(LettaConfig(id = "default", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = ""))
        val authenticator = answering(success = true)
        val store = WebPairedHostStore(config, WebSettingsStore(settings))
        val flow = PairingFlow(RelayPairingRedeemer(authenticator), store, "Browser", nowMs = { 1_000L })

        assertIs<PairingState.ConfirmHost>(flow.submit(pairLink()))
        assertEquals(0, authenticator.dials.size)
        val paired = assertIs<PairingState.Paired>(flow.confirm())

        assertEquals("iroh://$nodeId", paired.host.serverUrl)
        assertEquals(1, authenticator.dials.size)
        assertEquals("iroh://$nodeId", WebSettingsStore(settings).load().serverUrl)
    }
}
