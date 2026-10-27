package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.numberOrNull

import com.letta.mobile.util.textOrNull

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.util.Telemetry
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** letta-mobile-by4vm.112: host failure lines carry the error and stay sparse under a redial storm. */
class IrohFailureLogTest {
    private var ownedTelemetryHistory: AutoCloseable? = null

    @kotlin.test.BeforeTest
    fun installOwnedTelemetryHistory() {
        ownedTelemetryHistory = com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder())
    }

    @kotlin.test.AfterTest
    fun detachOwnedTelemetryHistory() {
        ownedTelemetryHistory?.close()
    }

    @Test
    fun repeatedFailureLogsBurstThenEveryNthWithRunningCount() {
        val tag = "IrohFailureLogTest-${System.nanoTime()}"
        val log = IrohFailureLog(tag, "x.failed", burst = 3, every = 50)
        val refused = IllegalStateException("peer doesn't support any known protocol")
        val relayed = IncomingRemote("relay", "cbb636387e92")
        repeat(120) { log.record(refused, alpn = "/letta/appserver/0", origin = relayed) }
        log.record(IllegalStateException("timed out"))

        // snapshot() is newest-first; reverse it into emit order.
        val lines = Telemetry.snapshot().filter { it.tag == tag }.asReversed()
        assertEquals(listOf(1L, 2L, 3L, 50L, 100L, 1L), lines.map { it.attrs["count"]?.numberOrNull })
        assertEquals("peer doesn't support any known protocol", lines.first().attrs["error"]?.textOrNull)
        assertEquals("/letta/appserver/0", lines.first().attrs["alpn"]?.textOrNull)
        assertEquals("relay", lines.first().attrs["path"]?.textOrNull)
        assertEquals("cbb636387e92", lines.first().attrs["remote"]?.textOrNull)
        assertEquals("IllegalStateException", lines.first().attrs["errorKind"]?.textOrNull)
        assertEquals("timed out", lines.last().attrs["error"]?.textOrNull)
    }

    @Test
    fun distinctMessagesAreCountedSeparately() {
        val log = IrohFailureLog("t", "e")
        assertEquals(1L, log.countFor("a"))
        assertEquals(2L, log.countFor("a"))
        assertEquals(1L, log.countFor("b"))
    }

    @Test
    fun messageFallsBackToTheClassNameWhenAbsent() {
        assertEquals("IllegalStateException", IrohFailureLog.errorMessage(IllegalStateException()))
        assertEquals("330415cc15c1", IrohFailureLog.remotePrefix("330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f"))
    }

    /**
     * The a2a receiver is a protocol of the host endpoint: the endpoint accepts its ALPN for an
     * outbound dial (it fails only because nothing is bound), and refuses an unregistered one.
     */
    @Test
    fun hostEndpointRegistersTheA2aAlpnOfItsReceiver() {
        val receiver = IrohAgentMessageReceiver(
            router = IrohAgentMessageRouter(ownAgentId = ""),
            conversationsFor = { emptyList() },
            onDeliver = { _, _ -> DeliveryOutcome(delivered = false) },
        )
        fun host(withA2a: Boolean) = IrohNodeEndpoint(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
            protocolHandlers = if (withA2a) listOf(receiver) else emptyList(),
        )
        val remote = EndpointAddr(EndpointId.fromBytes(ByteArray(32)), null, emptyList())
        runBlocking {
            assertFailsWith<IllegalStateException> { host(withA2a = true).connect(remote, IrohAgentMessage.ALPN) }
            assertFailsWith<IllegalArgumentException> { host(withA2a = false).connect(remote, IrohAgentMessage.ALPN) }
        }
    }
}
