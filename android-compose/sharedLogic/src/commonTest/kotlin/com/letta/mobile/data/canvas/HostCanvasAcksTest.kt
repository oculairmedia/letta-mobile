package com.letta.mobile.data.canvas

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** letta-mobile-qygvv.21: a host publish waits for the relay's answer to every op it sent. */
class HostCanvasAcksTest {
    private val sent = listOf(op("op-1"), op("op-2"))

    private fun op(id: String) = CanvasOp.SetBackgroundOp(id, "agent-1", 1L, "#ffffffff")

    private fun ack(id: String, cursor: Long) = CanvasRelayMessage.Ack(TOPIC, id, cursor)

    @Test
    fun aRejectionThatArrivesLateIsStillARefusal() = runTest {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        replies.send(ack("op-1", 4L))
        launch {
            delay(50.milliseconds)
            replies.send(CanvasRelayMessage.Rejected(TOPIC, "op-2", "not joined"))
        }

        val outcome = HostCanvasAcks(sent).await(replies, TIMEOUT)

        assertTrue("not joined" in assertIs<HostCanvasPublish.Denied>(outcome).reason)
    }

    @Test
    fun everyOpAcknowledgedIsPublishedAtTheHighestCursor() = runTest {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        replies.send(CanvasRelayMessage.CaughtUp(TOPIC, 3L))
        replies.send(ack("op-1", 4L))
        launch {
            delay(50.milliseconds)
            replies.send(ack("op-2", 5L))
        }

        assertEquals(HostCanvasPublish.Published(5L), HostCanvasAcks(sent).await(replies, TIMEOUT))
    }

    @Test
    fun silencePastTheTimeoutIsNotReportedAsPublished() = runTest {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        replies.send(ack("op-1", 4L))

        val outcome = HostCanvasAcks(sent).await(replies, TIMEOUT)

        assertTrue("did not acknowledge 1 op" in assertIs<HostCanvasPublish.Denied>(outcome).reason)
    }

    @Test
    fun aRefusedConnectionIsARefusal() = runTest {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        replies.send(CanvasRelayMessage.Refused("empty topic or canvas id"))

        val outcome = HostCanvasAcks(sent).await(replies, TIMEOUT)

        assertTrue("empty topic" in assertIs<HostCanvasPublish.Denied>(outcome).reason)
    }

    private companion object {
        const val TOPIC = "topic-1"
        val TIMEOUT = 5.seconds
    }
}
