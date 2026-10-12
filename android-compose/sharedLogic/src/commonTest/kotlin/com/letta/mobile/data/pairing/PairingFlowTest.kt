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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class PairingFlowTest {
    private val nodeId = "ab".repeat(30) + "cdef"
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
    private var dials = 0

    private fun flow(outcome: suspend (ScannedInvite) -> RedeemOutcome) = PairingFlow(
        redeemer = { invite, _ -> dials++; redeemed = invite; outcome(invite) },
        store = SettingsPairedHostStore(sink),
        deviceName = "pixel",
        nowMs = { now },
    )

    private fun pairedOutcome(invite: ScannedInvite) = RedeemOutcome.Paired(PairedHost(invite.nodeId, "${invite.nodeId}@h:1"), alreadyPaired = false)

    private suspend fun PairingFlow.submitAndConfirm(raw: String): PairingState {
        val prompted = submit(raw)
        return if (prompted is PairingState.ConfirmHost) confirm() else prompted
    }

    @Test
    fun scanStopsAtTheTrustPromptAndDialsOnlyAfterConnect() = runTest {
        val f = flow(::pairedOutcome)
        val prompt = assertIs<PairingState.ConfirmHost>(f.submit(PairLink(envelope(), "h:1").encode()))
        assertEquals(0, dials, "the trust prompt must come before any dial")
        assertEquals("ababab…abcdef", prompt.hostShortId)
        assertEquals(100_000L, prompt.expiresAtMs)
        val paired = assertIs<PairingState.Paired>(f.confirm())
        assertEquals(1, dials)
        assertEquals("pixel", paired.deviceName)
        assertEquals("iroh://$nodeId@h:1", paired.host.serverUrl)
        val config = sink.saved.single()
        assertNull(config.accessToken, "pairing must not persist the one-time invite")
        assertEquals(config.id, sink.active)
        assertEquals("h:1", redeemed?.dial)
        assertEquals(secret, redeemed?.inviteToken)
    }

    @Test
    fun deepLinksOfBothSchemesNeverAutoConnect() = runTest {
        listOf("meridian://pair?qr=", "letta://pair?qr=").forEach { prefix ->
            val f = flow(::pairedOutcome)
            assertIs<PairingState.ConfirmHost>(f.submit(prefix + envelope()))
            f.cancel()
            assertEquals(PairingState.Idle, f.state.value)
        }
        assertEquals(0, dials)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun cancelOnThePromptDropsTheCodeSoConfirmCannotDialIt() = runTest {
        val f = flow(::pairedOutcome)
        f.submit(envelope())
        f.cancel()
        assertEquals(PairingState.Idle, f.confirm())
        assertEquals(0, dials)
    }

    @Test
    fun repeatedFramesWhileThePromptIsUpAreIgnored() = runTest {
        val f = flow(::pairedOutcome)
        val first = f.submit(envelope())
        assertEquals(first, f.submit("https://example.com"))
        assertIs<PairingState.ConfirmHost>(f.state.value)
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
        assertIs<PairingState.Paired>(flow(::pairedOutcome).submitAndConfirm(envelope()))
    }

    @Test
    fun aCodeThatExpiresWhileThePromptIsOpenIsNotDialled() = runTest {
        val f = flow(::pairedOutcome)
        f.submit(envelope())
        now = 100_000L + PairingFlow.CLOCK_SKEW_ALLOWANCE_MS
        assertEquals(PairingState.Failed(PairingError.EXPIRED), f.confirm())
        assertEquals(0, dials)
    }

    @Test
    fun hostRejectionsSurfaceAsTypedErrorsAndSaveNothing() = runTest {
        listOf(PairingError.INVITE_INVALID, PairingError.EXPIRED, PairingError.DENIED, PairingError.HOST_MISMATCH).forEach { error ->
            assertEquals(PairingState.Failed(error), flow { RedeemOutcome.Failed(error) }.submitAndConfirm(envelope()))
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun anUnreachableHostIsRetryableWithTheSameCode() = runTest {
        var reachable = false
        val f = flow { if (reachable) pairedOutcome(it) else error("connect refused") }
        assertEquals(PairingState.Failed(PairingError.HOST_UNREACHABLE, retryable = true), f.submitAndConfirm(envelope()))
        assertTrue(sink.saved.isEmpty(), "fail closed: an unreachable host is never saved")
        reachable = true
        assertIs<PairingState.Paired>(f.retry())
        assertEquals(2, dials)
    }

    @Test
    fun aSilentHostTimesOutAsUnreachable() = runTest {
        val f = flow { awaitCancellation() }
        assertEquals(PairingState.Failed(PairingError.HOST_UNREACHABLE, retryable = true), f.submitAndConfirm(envelope()))
    }

    @Test
    fun cancellingWhileVerifyingNeverPersistsALateAnswer() = runTest {
        val answer = CompletableDeferred<RedeemOutcome>()
        val f = flow { answer.await() }
        f.submit(envelope())
        val job = launch { f.confirm() }
        runCurrent()
        assertIs<PairingState.Pairing>(f.state.value)
        f.cancel()
        answer.complete(RedeemOutcome.Paired(PairedHost(nodeId, nodeId), alreadyPaired = false))
        job.join()
        assertEquals(PairingState.Idle, f.state.value)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun alreadyPairedDeviceStillLandsConnectedAndRepairingReusesTheConfig() = runTest {
        sink.existing = listOf(LettaConfig(id = "old", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://$nodeId@old:1"))
        val state = flow { RedeemOutcome.Paired(PairedHost(it.nodeId, it.nodeId), alreadyPaired = true) }.submitAndConfirm(envelope())
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
