package com.letta.mobile.data.transport.iroh

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * g3cva.2 verification (added by main thread): prove that bytes/frames actually
 * move over a REAL iroh QUIC bi-stream between two in-process endpoints — the
 * load-bearing claim the adapter bead must support. The worker's integration
 * test deferred this ("hangs without a server"); this test supplies the missing
 * accept side using the real iroh 1.0 Kotlin API:
 *   server: endpoint.acceptNext() -> Incoming.accept() -> Accepting.connect()
 *           -> Connection.acceptBi() -> BiStream
 *   client: endpoint.connect(addr, alpn) -> Connection.openBi() -> BiStream
 * Then echo a framed payload and assert it round-trips byte-for-byte.
 *
 * Loopback/direct addresses only — no relay/discovery needed because both
 * endpoints share the host and the client dials the server's discovered addr.
 */
class IrohLoopbackRoundTripTest {

    private val alpn = "/letta/appserver/0".toByteArray()

    @Test
    fun appServerFrameRoundTripsOverRealIrohBiStream() = runBlocking {
        val server = Endpoint.bind(EndpointOptions(alpns = listOf(alpn)))
        val client = Endpoint.bind(EndpointOptions())
        try {
            val serverAddr = server.addr()

            // Server: accept one connection, accept one bi-stream, echo a line.
            val serverJob = async(Dispatchers.IO) {
                withTimeout(SERVER_TIMEOUT_MS) {
                    val incoming = requireNotNull(server.acceptNext()) { "server.acceptNext() returned null" }
                    val accepting = incoming.accept()
                    val conn: Connection = accepting.connect()
                    val bi = conn.acceptBi()
                    val recv = bi.recv()
                    val line = recv.readToEnd(MAX_FRAME_BYTES)
                    // Echo it straight back on the same bi-stream's send half.
                    val send = bi.send()
                    send.writeAll(line)
                    send.finish()
                    String(line)
                }
            }

            // Client: connect by the server's addr, open a bi-stream, send a
            // synthetic App Server v2 frame, read the echo back.
            val frame = """{"type":"input","runtime":{"agent_id":"agent-x","conversation_id":"conv-y"}}""".toByteArray()
            val clientEcho = withTimeout(CLIENT_TIMEOUT_MS) {
                val conn = client.connect(serverAddr, alpn)
                val bi = conn.openBi()
                val send = bi.send()
                send.writeAll(frame)
                send.finish()
                val echo = bi.recv().readToEnd(MAX_FRAME_BYTES)
                String(echo)
            }

            val serverSaw = serverJob.await()
            assertEquals(String(frame), serverSaw, "server must receive the exact frame over iroh")
            assertEquals(String(frame), clientEcho, "client must read the exact echoed frame back over iroh")
        } finally {
            runCatching { client.shutdown() }
            runCatching { client.close() }
            runCatching { server.shutdown() }
            runCatching { server.close() }
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 65536u
        const val SERVER_TIMEOUT_MS = 30_000L
        const val CLIENT_TIMEOUT_MS = 30_000L
    }
}
