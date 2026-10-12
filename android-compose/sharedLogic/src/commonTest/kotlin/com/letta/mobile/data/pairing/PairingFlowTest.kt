package com.letta.mobile.data.pairing

import com.letta.mobile.data.model.LettaConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class PairingFlowTest {
    private val nodeId = "ab".repeat(32)
    private val secret = "invite:" + "5".repeat(64)
    private var now = 1_000L

    private fun envelope(version: Int = 1, node: String = nodeId, expires: Long = 100_000L, token: String = secret): String {
        val json = """{"version":$version,"node_id":"$node","signed_secret":"$token","expires_at_ms":$expires,"signature":"s"}"""
        return "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
    }

    private class RecordingSink : BackendConfigSink {
        val saved = mutableListOf<LettaConfig>()
        var active: String? = null
        var existing: List<LettaConfig> = emptyList()

        override fun currentConfigs(): List<LettaConfig> = existing + saved

        override suspend fun save(config: LettaConfig) {
            saved += config
        }

        override suspend fun activate(configId: String) {
            active = configId
        }
    }

    private val sink = RecordingSink()
    private var redeemed: ScannedInvite? = null

    private fun flow(outcome: (ScannedInvite) -> RedeemOutcome) = PairingFlow(
        redeemer = { invite, _ -> redeemed = invite; outcome(invite) },
        store = SettingsPairedHostStore(sink),
        deviceName = "pixel",
        nowMs = { now },
    )

    private fun pairedOutcome(invite: ScannedInvite) = RedeemOutcome.Paired(PairedHost(invite.nodeId, "${invite.nodeId}@h:1"), alreadyPaired = false)

    @Test
    fun scanPairsPersistsWithoutACredentialAndActivatesTheHost() = runTest {
        val state = flow(::pairedOutcome).submit(PairLink(envelope(), "h:1").encode())
        val paired = assertIs<PairingState.Paired>(state)
        assertEquals("iroh://$nodeId@h:1", paired.host.serverUrl)
        val config = sink.saved.single()
        assertNull(config.accessToken, "pairing must not persist the one-time invite")
        assertEquals(config.id, sink.active)
        assertEquals("h:1", redeemed?.dial)
        assertEquals(secret, redeemed?.inviteToken)
    }

    @Test
    fun foreignMalformedAndFutureVersionCodesFailLocally() = runTest {
        val f = flow { error("must not dial") }
        assertEquals(PairingState.Failed(PairingError.NOT_A_PAIRING_CODE), f.submit("https://example.com"))
        assertEquals(PairingState.Failed(PairingError.MALFORMED), f.submit("letta-qr-v1.!!!"))
        assertEquals(PairingState.Failed(PairingError.UNSUPPORTED_VERSION), f.submit(envelope(version = 2)))
        assertEquals(PairingState.Failed(PairingError.MALFORMED), f.submit(envelope(node = "not-hex")))
        assertEquals(PairingState.Failed(PairingError.MALFORMED), f.submit(envelope(token = "bearer-token")))
        assertNull(redeemed)
    }

    @Test
    fun expiredCodeNeverReachesTheHostButSmallClockSkewIsTolerated() = runTest {
        now = 100_000L + PairingFlow.CLOCK_SKEW_ALLOWANCE_MS
        assertEquals(PairingState.Failed(PairingError.EXPIRED), flow { error("no dial") }.submit(envelope()))
        now = 100_000L + PairingFlow.CLOCK_SKEW_ALLOWANCE_MS - 1
        assertIs<PairingState.Paired>(flow(::pairedOutcome).submit(envelope()))
    }

    @Test
    fun hostRejectionsSurfaceAsTypedErrorsAndSaveNothing() = runTest {
        listOf(PairingError.INVITE_INVALID, PairingError.EXPIRED, PairingError.DENIED, PairingError.HOST_MISMATCH).forEach { error ->
            assertEquals(PairingState.Failed(error), flow { RedeemOutcome.Failed(error) }.submit(envelope()))
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun aThrowingTransportIsReportedAsHostUnreachable() = runTest {
        assertEquals(PairingState.Failed(PairingError.HOST_UNREACHABLE), flow { error("connect refused") }.submit(envelope()))
    }

    @Test
    fun alreadyPairedDeviceStillLandsConnectedAndRepairingReusesTheConfig() = runTest {
        sink.existing = listOf(LettaConfig(id = "old", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://$nodeId@old:1"))
        val state = flow { RedeemOutcome.Paired(PairedHost(it.nodeId, it.nodeId), alreadyPaired = true) }.submit(envelope())
        assertEquals(true, assertIs<PairingState.Paired>(state).alreadyPaired)
        assertEquals("old", sink.saved.single().id)
    }

    @Test
    fun scannedInviteNeverPrintsTheSecret() {
        val invite = assertIs<ParsedInvite.Valid>(ScannedInviteParser.parse(envelope())).invite
        assertFalse(secret in invite.toString())
        assertEquals(nodeId.take(8), invite.hostFingerprint)
    }
}
