package com.letta.mobile.ui.pairing.composition

import com.letta.mobile.data.pairing.PairLink
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingRedeemer
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.data.pairing.RedeemOutcome
import com.letta.mobile.data.pairing.composition.PairingNetwork
import com.letta.mobile.data.pairing.composition.PairingPorts
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.ui.pairing.AgentChoice
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** letta-mobile-wisqe.1: every slot of the install-and-pair card is bound from the platform's ports. */
@OptIn(ExperimentalEncodingApi::class)
class InstallAndPairBindingsFactoryTest {
    private val nodeId = "ab".repeat(30) + "cdef"

    private class FakePorts : PairingPorts {
        var now = 1_000L
        val dialledAs = mutableListOf<String>()
        val saved = mutableListOf<PairedHost>()

        override suspend fun loadIdentityKey(): ByteArray = byteArrayOf(9)

        override val network = object : PairingNetwork {
            override fun redeemer(identityKey: suspend () -> ByteArray) = PairingRedeemer { invite, deviceName ->
                dialledAs += "$deviceName/${identityKey().single()}"
                RedeemOutcome.Paired(PairedHost(invite.nodeId, invite.nodeId), alreadyPaired = false)
            }

            override fun nodeIdOf(secretKey: ByteArray): String = "self-${secretKey.single()}"
        }
        override val hostStore = PairedHostStore { saved += it }
        override val deviceName = "laptop"
        override val adminRpc = AdminRpcInvoker { _, _ -> null }
        override val qrEncoder = QrEncoder { QrModules(1) { _, _ -> true } }

        override fun nowMs(): Long = now
    }

    private class FakeShell : PairingShellPorts {
        val events = mutableListOf<String>()
        override val agents = listOf(AgentChoice("agent-1", "Ada"))
        override val pendingLink = "meridian://pair?qr=pending"

        override fun consumePendingLink() {
            events += "consumed"
        }

        override fun openChat() {
            events += "chat"
        }

        override fun shareLink(link: String) {
            events += "share:$link"
        }
    }

    private fun link(): String {
        val json = """{"version":1,"node_id":"$nodeId","signed_secret":"invite:${"5".repeat(64)}","expires_at_ms":100000,"signature":"s"}"""
        val envelope = "letta-qr-v1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        return PairLink(envelope, null).encode()
    }

    @Test
    fun everyCardSlotIsBoundFromThePorts() = runTest {
        val ports = FakePorts()
        val shell = FakeShell()
        val bindings = installAndPairBindings(ports, shell)

        val flow = bindings.joinFlow()
        assertNotSame(flow, bindings.joinFlow(), "each card gets its own flow")
        assertIs<PairingState.ConfirmHost>(flow.submit(link()))
        assertIs<PairingState.Paired>(flow.confirm())
        assertEquals(listOf("laptop/9"), ports.dialledAs)
        assertEquals(listOf(PairedHost(nodeId, nodeId)), ports.saved)

        assertSame(ports.qrEncoder, bindings.qrEncoder)
        assertSame(ports.adminRpc, bindings.rpc)
        assertEquals(shell.agents, bindings.agents)
        ports.now = 77L
        assertEquals(77L, bindings.nowMs())
        assertEquals("self-9", bindings.selfNodeId())
        assertEquals(shell.pendingLink, bindings.pendingLink)

        bindings.onPendingLinkConsumed()
        bindings.onOpenChat()
        checkNotNull(bindings.onShareLink) { "share must be bound" }("meridian://pair?qr=x")
        assertEquals(listOf("consumed", "chat", "share:meridian://pair?qr=x"), shell.events)
    }
}
