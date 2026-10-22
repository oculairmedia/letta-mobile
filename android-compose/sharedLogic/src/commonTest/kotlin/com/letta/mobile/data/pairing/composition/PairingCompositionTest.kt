package com.letta.mobile.data.pairing.composition

import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairLink
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-wisqe.1: every piece of the pairing composition is built from the platform's ports. */
@OptIn(ExperimentalEncodingApi::class)
class PairingCompositionTest {
    private val nodeId = "ab".repeat(30) + "cdef"
    private val key = ByteArray(32) { 7 }

    private class FakeNetwork : PairingNetwork {
        val keys = mutableListOf<ByteArray>()
        val devices = mutableListOf<String>()

        override fun redeemer(identityKey: suspend () -> ByteArray) = PairingRedeemer { invite, deviceName ->
            keys += identityKey()
            devices += deviceName
            RedeemOutcome.Paired(PairedHost(invite.nodeId, "${invite.nodeId}@h:1"), alreadyPaired = false)
        }

        override fun nodeIdOf(secretKey: ByteArray): String = "node-" + secretKey.first()
    }

    private inner class FakePorts : PairingPorts {
        var now = 1_000L
        var identityFailure: Exception? = null
        val fakeNetwork = FakeNetwork()
        val saved = mutableListOf<PairedHost>()
        val calls = mutableListOf<String>()
        val encoded = mutableListOf<String>()

        override suspend fun loadIdentityKey(): ByteArray = identityFailure?.let { throw it } ?: key

        override val network: PairingNetwork get() = fakeNetwork
        override val hostStore = PairedHostStore { saved += it }
        override val deviceName = "pixel"
        override val adminRpc = AdminRpcInvoker { method, params ->
            calls += method
            when (method) {
                "pair.invite.create" -> buildJsonObject {
                    put("pair_link", "meridian://pair?qr=letta-qr-v1.x")
                    put("invite_id", "inv-1")
                    put("expires_at_ms", now + params["ttl_ms"]!!.jsonPrimitive.long)
                }
                else -> JsonArray(emptyList())
            }
        }
        override val qrEncoder = QrEncoder { content -> encoded += content; QrModules(1) { _, _ -> true } }

        override fun nowMs(): Long = now
    }

    private fun link(expires: Long = 100_000L): String {
        val secret = "invite:" + "5".repeat(64)
        val json = """{"version":1,"node_id":"$nodeId","signed_secret":"$secret","expires_at_ms":$expires,"signature":"s"}"""
        val envelope = "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        return PairLink(envelope, "h:1").encode()
    }

    @Test
    fun joinFlowDialsWithTheIdentityPortAndSavesThroughTheHostStore() = runTest {
        val ports = FakePorts()
        val flow = PairingComposition(ports).joinFlow()
        assertIs<PairingState.ConfirmHost>(flow.submit(link()))
        val paired = assertIs<PairingState.Paired>(flow.confirm())
        assertContentEquals(key, ports.fakeNetwork.keys.single())
        assertEquals(listOf("pixel"), ports.fakeNetwork.devices)
        assertEquals("pixel", paired.deviceName)
        assertEquals(listOf(PairedHost(nodeId, "$nodeId@h:1")), ports.saved)
    }

    @Test
    fun joinFlowReadsExpiryFromTheClockPort() = runTest {
        val ports = FakePorts().apply { now = 10_000_000L }
        val flow = PairingComposition(ports).joinFlow()
        assertEquals(PairingError.EXPIRED, assertIs<PairingState.Failed>(flow.submit(link(expires = 100_000L))).error)
    }

    @Test
    fun inviteSessionMintsOverTheAdminRpcAndEncodesWithTheQrPort() = runTest {
        val ports = FakePorts()
        val session = PairingComposition(ports).inviteSession(ports.adminRpc, TestScope(StandardTestDispatcher(testScheduler)))
        session.mint(PairInviteRequest("tablet"))
        advanceUntilIdle()
        assertTrue("pair.invite.create" in ports.calls, "minted over the admin_rpc port: ${ports.calls}")
        assertEquals(listOf("meridian://pair?qr=letta-qr-v1.x"), ports.encoded)
        assertEquals(1_000L + PairInviteRequest.DEFAULT_TTL_MS, session.state.value.invite?.expiresAtMs)
    }

    @Test
    fun selfNodeIdIsTheNetworksIdForTheIdentityKeyAndNullWhenItCannotLoad() = runTest {
        val ports = FakePorts()
        val pairing = PairingComposition(ports)
        assertEquals("node-7", pairing.selfNodeId())
        ports.identityFailure = IllegalStateException("keystore locked")
        assertNull(pairing.selfNodeId())
    }

    @Test
    fun selfNodeIdPropagatesCancellation() = runTest {
        val ports = FakePorts().apply { identityFailure = CancellationException("left") }
        val result = runCatching { PairingComposition(ports).selfNodeId() }
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    @Test
    fun passesThroughTheRpcEncoderAndClock() {
        val ports = FakePorts()
        val pairing = PairingComposition(ports)
        assertSame(ports.adminRpc, pairing.adminRpc)
        assertSame(ports.qrEncoder, pairing.qrEncoder)
        ports.now = 42L
        assertEquals(42L, pairing.nowMs())
    }
}
