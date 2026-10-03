package com.letta.mobile.data.plugin.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The transport-agnostic peer (letta-mobile-s416w.25): correlation under concurrency, deadlines
 * both ways, `$/cancel` both ways, overload, unknown methods and a closed connection.
 */
class LcpPeerTest {
    /** A host peer with no guard, and the raw plugin end of its transport. */
    private class RawRig(scope: CoroutineScope, limits: LcpPeerLimits = LcpPeerLimits(), side: LcpSide = LcpSide.HOST) {
        private val ends = LoopbackLcpTransport.pair()
        val peer = LcpPeer(ends.first, LcpPeerConfig(side, limits = limits), scope)
        val remote: LcpTransport = ends.second

        suspend fun send(raw: String) = remote.send(raw)

        /** The next message the peer sent, decoded. */
        suspend fun next(): JsonRpcMessage {
            val frame = assertIs<LcpInboundFrame.Text>(remote.receive())
            return assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode(frame.text)).message
        }

        suspend fun nextError(): JsonRpcError = assertIs<JsonRpcMessage.Failure>(next()).error
    }

    /** Two joined peers with no guard: a host and a plugin. */
    private class PairRig(scope: CoroutineScope) {
        private val ends = LoopbackLcpTransport.pair()
        val host = LcpPeer(ends.first, LcpPeerConfig(LcpSide.HOST), scope)
        val plugin = LcpPeer(ends.second, LcpPeerConfig(LcpSide.PLUGIN), scope)

        fun start() {
            host.start()
            plugin.start()
        }
    }

    private fun TestScope.pair(serve: PairRig.() -> Unit): PairRig = PairRig(backgroundScope).apply(serve).apply { start() }

    private fun numbered(n: Int): JsonObject = buildJsonObject { put("n", n) }

    @Test
    fun answersFindTheirCallersWhenManyCallsInterleave() = runTest {
        val rig = pair {
            plugin.handle(LcpMethod.INVOKE) { params ->
                val n = params.getValue("n").jsonPrimitive.int
                delay((50 - n) * 10L)
                numbered(n * 2)
            }
        }
        val answers = (1..50).map { n -> async { rig.host.request(LcpMethod.INVOKE, numbered(n)) } }.awaitAll()
        assertEquals((1..50).map { numbered(it * 2) }, answers)
        assertEquals(0, rig.host.inFlight.value)
    }

    @Test
    fun aCallPastItsTimeoutFailsAndCancelsTheRemoteHandler() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val rig = pair {
            plugin.handle(LcpMethod.HEALTH) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val failure = assertFailsWith<LcpCallException> { rig.host.request(LcpMethod.HEALTH, JsonObject(emptyMap()), timeout = 1.seconds) }
        assertEquals(LcpErrorCode.DEADLINE_EXCEEDED, failure.code)
        cancelled.await()
    }

    @Test
    fun aCancelledCallerCancelsTheRemoteHandler() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val rig = pair {
            plugin.handle(LcpMethod.INVOKE) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val caller = launch { rig.host.request(LcpMethod.INVOKE, JsonObject(emptyMap())) }
        started.await()
        caller.cancel()
        cancelled.await()
    }

    @Test
    fun theReceiverHoldsAHandlerToTheMethodsDeadline() = runTest {
        val rig = pair { plugin.handle(LcpMethod.HEALTH) { awaitCancellation() } }
        val failure = assertFailsWith<LcpCallException> { rig.host.request(LcpMethod.HEALTH, JsonObject(emptyMap()), timeout = 60.seconds) }
        assertEquals(LcpErrorCode.DEADLINE_EXCEEDED, failure.code)
    }

    @Test
    fun aRemoteCancelIsAnsweredRequestCancelled() = runTest {
        val rig = RawRig(backgroundScope, side = LcpSide.PLUGIN)
        rig.peer.handle(LcpMethod.INVOKE) { awaitCancellation() }
        rig.peer.start()
        rig.send("""{"jsonrpc":"2.0","id":"call-9","method":"action.invoke","params":{}}""")
        rig.send("""{"jsonrpc":"2.0","method":"${'$'}/cancel","params":{"id":"call-9"}}""")
        val answer = assertIs<JsonRpcMessage.Failure>(rig.next())
        assertEquals(JsonPrimitive("call-9"), answer.id)
        assertEquals(LcpErrorCode.REQUEST_CANCELLED, answer.error.code)
    }

    @Test
    fun unknownMethodsAndMethodsOfTheOtherDirectionAreNotFound() = runTest {
        val rig = RawRig(backgroundScope)
        rig.peer.start()
        rig.send("""{"jsonrpc":"2.0","id":1,"method":"plugin.unknown","params":{}}""")
        assertEquals(LcpErrorCode.METHOD_NOT_FOUND, rig.nextError().code)
        rig.send("""{"jsonrpc":"2.0","id":2,"method":"action.invoke","params":{}}""")
        assertEquals(LcpErrorCode.METHOD_NOT_FOUND, rig.nextError().code)
        rig.send("not json")
        assertEquals(LcpErrorCode.PARSE_ERROR, rig.nextError().code)
    }

    @Test
    fun requestsBeyondTheInboundLimitAreAnsweredOverloaded() = runTest {
        val rig = RawRig(backgroundScope, LcpPeerLimits(maxConcurrentInbound = 1))
        rig.peer.handle(LcpMethod.EMIT) { awaitCancellation() }
        rig.peer.start()
        rig.send("""{"jsonrpc":"2.0","id":1,"method":"host.emit","params":{}}""")
        rig.send("""{"jsonrpc":"2.0","id":2,"method":"host.emit","params":{}}""")
        val answer = assertIs<JsonRpcMessage.Failure>(rig.next())
        assertEquals(JsonPrimitive(2), answer.id)
        assertEquals(LcpErrorCode.OVERLOADED, answer.error.code)
    }

    @Test
    fun aHandlerThatThrowsIsAnsweredWithoutEndingThePeer() = runTest {
        val rig = pair {
            plugin.handle(LcpMethod.HEALTH) { error("boom") }
            plugin.handle(LcpMethod.ACTIVATE) { JsonObject(emptyMap()) }
        }
        val failure = assertFailsWith<LcpCallException> { rig.host.request(LcpMethod.HEALTH, JsonObject(emptyMap())) }
        assertEquals(LcpErrorCode.INTERNAL_ERROR, failure.code)
        assertEquals(JsonObject(emptyMap()), rig.host.request(LcpMethod.ACTIVATE, JsonObject(emptyMap())))
    }

    @Test
    fun aClosedConnectionFailsTheCallsWaitingOnIt() = runTest {
        val rig = RawRig(backgroundScope)
        rig.peer.start()
        val waiting = async { runCatching { rig.peer.request(LcpMethod.HEALTH, JsonObject(emptyMap())) }.exceptionOrNull() }
        assertIs<JsonRpcMessage.Request>(rig.next())
        rig.remote.close()
        assertEquals(LcpErrorCode.CLOSED, assertIs<LcpCallException>(waiting.await()).code)
    }

    @Test
    fun anOversizedFrameIsAnsweredWithANullIdError() = runTest {
        val ends = LoopbackLcpTransport.pair()
        val oversized = object : LcpTransport by ends.first {
            override suspend fun receive(): LcpInboundFrame? = ends.first.receive()?.let { LcpInboundFrame.Oversized(9_000_000) }
        }
        LcpPeer(oversized, LcpPeerConfig(LcpSide.HOST), backgroundScope).start()
        ends.second.send("{}")
        val answer = JsonRpcCodec.decode(assertIs<LcpInboundFrame.Text>(ends.second.receive()).text)
        val failure = assertIs<JsonRpcMessage.Failure>(assertIs<JsonRpcDecoding.Decoded>(answer).message)
        assertEquals(LcpErrorCode.MESSAGE_TOO_LARGE, failure.error.code)
        assertEquals(null, failure.id)
    }

    @Test
    fun notificationsRunInArrivalOrder() = runTest {
        val seen = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val rig = pair {
            plugin.handle(LcpMethod.ELEMENT_EVENT) { params ->
                val n = params.getValue("n").jsonPrimitive.int
                delay((10 - n) * 5L)
                seen += n
                if (n == 10) done.complete(Unit)
                params
            }
        }
        (1..10).forEach { rig.host.notify(LcpMethod.ELEMENT_EVENT, numbered(it)) }
        done.await()
        assertEquals((1..10).toList(), seen)
    }
}
