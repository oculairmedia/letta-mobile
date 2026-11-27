package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecordingTest
import com.letta.mobile.util.numberOrNull
import com.letta.mobile.util.textOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The endpoint's shutdown-drain seam the wrapper's shutdown path drives: [IrohNodeEndpoint] hands
 * its generation's [NodeTurnHost] the drain (refusing new turn inputs), waits for the live turns
 * through it, and a second shutdown signal cuts the wait through the same seam. No QUIC endpoint
 * is bound: the turn host generation is started directly, exactly as [IrohNodeEndpoint.start] does.
 */
class IrohNodeEndpointShutdownDrainTest : TelemetryRecordingTest() {
    private val runtime = AppServerRuntimeScope("agent-endpoint-drain", "conv-endpoint-drain")
    private val agent = AgentId(runtime.agentId)
    private val conversation = ConversationId(runtime.conversationId)

    private fun endpoint(scope: CoroutineScope) = IrohNodeEndpoint(
        scope = scope,
        authPolicy = IrohAuthPolicy.InsecureAnonymousForTestOnly,
    )

    @Test
    fun anEndpointThatNeverStartedRefusesToDrain() = runTest {
        val undrained = endpoint(backgroundScope)
        assertFalse(undrained.beginShutdownDrain(5.minutes), "no turn-host generation, nothing to drain")
        // Waiting on a refused drain returns at once rather than hanging the shutdown path.
        undrained.awaitShutdownDrain()
        assertTrue(
            Telemetry.snapshot().none { it.name.startsWith("shutdown.drain.") },
            "a refused drain emits no drain telemetry",
        )
    }

    @Test
    fun theEndpointRefusesInputsAndCutsTheWaitThroughItsOwnSeam() = runTest {
        val served = endpoint(backgroundScope)
        val host = served.startTurnHost(FakeAppServerController())
        val endTurn = CompletableDeferred<Unit>()

        // One live turn on the endpoint generation's host, as a client input starts it: it ends
        // when the deferred completes, so it stays live until the test says otherwise.
        val writer = host.journal.openTurn(agent, conversation, CLIENT_MESSAGE_ID)
        backgroundScope.launch {
            host.run(
                NodeTurn(
                    conversationId = runtime.conversationId,
                    clientMessageId = CLIENT_MESSAGE_ID,
                    fanout = ConversationTurnFanout(
                        conversationId = runtime.conversationId,
                        runtime = runtime,
                        delivery = TurnFanoutDelivery(viewersFor = { emptySet() }),
                        initiatorViewer = null,
                        trackInitiatorFrame = writer::append,
                    ),
                    journal = writer,
                ) {
                    endTurn.await()
                },
            )
        }
        runCurrent()
        assertEquals(1, host.liveTurnCount)

        assertTrue(served.beginShutdownDrain(250.milliseconds), "the endpoint begins its generation's drain")
        assertFalse(served.beginShutdownDrain(250.milliseconds), "a drain begins only once")

        val waiting = async { served.awaitShutdownDrain() }
        advanceTimeBy(249)
        runCurrent()
        assertFalse(waiting.isCompleted, "the endpoint waits for its generation's live turn")

        served.cutShutdownDrainWait()
        runCurrent()
        assertTrue(waiting.isCompleted, "the second shutdown signal cuts the endpoint's wait at once")

        val done = assertNotNull(
            Telemetry.snapshot().lastOrNull { it.name == "shutdown.drain.done" },
            "the drain's end is telemetered",
        )
        assertEquals(1, done.attrs["waited_turns"]?.numberOrNull?.toInt())
        assertEquals(1, done.attrs["cut_turns"]?.numberOrNull?.toInt())
        assertEquals("cut", done.attrs["reason"]?.textOrNull)

        endTurn.complete(Unit)
        runCurrent()
        served.shutdown()
    }

    private companion object {
        const val CLIENT_MESSAGE_ID = "cm-endpoint-drain-1"
    }
}
