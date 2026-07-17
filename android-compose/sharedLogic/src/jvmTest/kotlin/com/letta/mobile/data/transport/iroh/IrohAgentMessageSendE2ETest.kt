package com.letta.mobile.data.transport.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * letta-mobile-bn008.2: OPT-IN real-loopback probe for the direct agent-to-agent
 * send path. Dials a second in-process Iroh endpoint (the "receiver") over the a2a
 * ALPN and asserts the envelope arrives exactly once and is acked. Gated like the
 * other iroh-ffi loopback tests (System property runIrohLiveE2E=true) so it never
 * runs (or flakes) in the default :sharedLogic:allTests gate; opt in to validate
 * against a real QUIC connection.
 */
class IrohAgentMessageSendE2ETest {

    @Before fun requireOptIn() {
        assumeTrue("set -DrunIrohLiveE2E=true to run the loopback a2a send probe", System.getProperty("runIrohLiveE2E") == "true")
    }

    private lateinit var senderEndpoint: Endpoint
    private lateinit var receiverEndpoint: Endpoint

    @After fun tearDown() {
        runBlocking {
            if (::senderEndpoint.isInitialized) runCatching { senderEndpoint.shutdown() }
            if (::receiverEndpoint.isInitialized) runCatching { receiverEndpoint.shutdown() }
        }
    }

    @Test
    fun sendDeliversEnvelopeExactlyOnceAndIsAcked() = runBlocking {
        senderEndpoint = Endpoint.bind(EndpointOptions(relayMode = RelayMode.defaultMode()))
        receiverEndpoint = Endpoint.bind(
            EndpointOptions(relayMode = RelayMode.defaultMode(), alpns = listOf(IrohAgentMessage.ALPN)),
        )
        receiverEndpoint.online()
        senderEndpoint.online()

        val received = AtomicInteger(0)
        var lastMsgId: String? = null

        // Minimal receiver loop: accept -> acceptBi -> read envelope -> ack.
        val listener = launch(Dispatchers.IO) {
            val incoming = receiverEndpoint.acceptNext() ?: return@launch
            val conn = incoming.accept().connect()
            conn.use { c ->
                val bi = c.acceptBi()
                bi.use { stream ->
                    val wire = IrohFrameCodec.readOne(stream.recv()) ?: return@launch
                    val msg = IrohAgentMessage.decode(wire)
                    received.incrementAndGet()
                    lastMsgId = msg.msgId
                    val send = stream.send()
                    IrohFrameCodec.write(send, IrohAgentMessageAck(msg.msgId, accepted = true).encode())
                    send.finish()
                }
            }
        }

        // Publish the receiver's address so the resolver can find it.
        val store = FileIrohAgentAddressStore(File.createTempFile("bn008-2", ".kv").apply { deleteOnExit() })
        val recvAddr = receiverEndpoint.addr()
        val nodeHex = recvAddr.id().toBytes().joinToString("") { "%02x".format(it) }
        val direct = withContext(Dispatchers.IO) { recvAddr.directAddresses().map { it } }
        store.register(IrohAgentAddress("agent-recv", nodeHex, direct))
        val resolver = IrohAgentAddressResolver(store)

        val sender = IrohAgentMessageSender(senderEndpoint, resolver)
        val result = sender.send(
            IrohAgentMessage("agent-send", "agent-recv", body = "hello a2a", msgId = "m-1", ts = 1L),
        )

        listener.join()
        val delivered = assertIs<AgentSendResult.Delivered>(result)
        assertEquals("m-1", delivered.msgId)
        assertEquals(1, received.get(), "the receiver must get the envelope exactly once")
        assertEquals("m-1", lastMsgId)
    }
}
