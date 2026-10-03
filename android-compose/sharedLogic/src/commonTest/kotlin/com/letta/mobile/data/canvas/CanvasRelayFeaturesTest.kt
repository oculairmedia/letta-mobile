package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.5: peers built before plugin elements (#1738) degrade safely. Such a peer cannot
 * decode set_plugin_element at all, so a host never sends one to an app that did not say it reads
 * them, and an app never sends one to a host that did not, which would refuse the whole connection.
 */
class CanvasRelayFeaturesTest {
    private val topic = CanvasRelayProtocol.conversationTopic("conv-1")
    private fun note(opId: String, lamport: Long) =
        CanvasOp.AddElementOp(opId, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, "el-$opId", """{"id":"el-$opId","type":"Text","text":"$opId"}""")

    /** A raw connection to [host] that joins as [features] say and records what it is sent. */
    private suspend fun raw(host: CanvasRelayHost, origin: String, features: List<String>): Pair<CanvasRelayHost.Session, MutableList<CanvasRelayMessage>> {
        val received = mutableListOf<CanvasRelayMessage>()
        val session = host.connect(origin) { received += it }
        session.receive(CanvasRelayMessage.Join(topic, "canvas-1", 0L, features))
        return session to received
    }

    private fun opsIn(messages: List<CanvasRelayMessage>) = messages.filterIsInstance<CanvasRelayMessage.Op>().map { it.op }

    @Test
    fun anAppBuiltBeforePluginElementsIsNeverSentOneInCatchUpOrFanOut() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" })
        val (writer, _) = raw(host, "new-app", CanvasRelayFeatures.SUPPORTED)
        writer.receive(CanvasRelayMessage.Publish(topic, note("n-1", 1)))
        writer.receive(CanvasRelayMessage.Publish(topic, CanvasPluginElementFixtures.place(2)))
        val (_, legacy) = raw(host, "old-app", features = emptyList())
        val (_, current) = raw(host, "newer-app", CanvasRelayFeatures.SUPPORTED)

        val batch = CanvasOp.BatchOp("b-1", "agent-1", 3, listOf(note("n-2", 3), CanvasPluginElementFixtures.progress(3, 0.5)))
        writer.receive(CanvasRelayMessage.Publish(topic, batch))
        writer.receive(CanvasRelayMessage.Publish(topic, CanvasPluginElementFixtures.remove(4)))

        val legacyOps = opsIn(legacy)
        assertEquals(listOf("n-1", "b-1"), legacyOps.map { it.opId }, "the old app gets every op it can read")
        assertEquals(listOf("n-2"), assertIs<CanvasOp.BatchOp>(legacyOps[1]).ops.map { it.opId }, "a batch without what it cannot")
        assertTrue(legacy.none { it is CanvasRelayMessage.Refused })
        assertEquals(4, opsIn(current).size, "an app that reads them gets them all")
        assertEquals(CanvasRelayFeatures.SUPPORTED, legacy.filterIsInstance<CanvasRelayMessage.Joined>().single().features)
    }

    @Test
    fun anAppHoldsBackPluginOpsFromAHostThatCannotReadThemAndSendsThemOnceItCan() = runTest {
        val store = InMemoryCanvasRelayStore()
        val host = CanvasRelayHost(store, hostId = { "host-1" })
        val phone = TestApp("phone", backgroundScope).open("conv-1")
        phone.client.expectHost(true)
        val legacy = LegacyHostConnection.to(host, "phone")
        val run = launch { phone.client.run(legacy) }
        runCurrent()

        phone.edit(note("n-1", 1))
        phone.edit(CanvasPluginElementFixtures.place(2, actor = CanvasSession.LOCAL_USER_ACTOR_ID))
        runCurrent()

        val published = legacy.sent.filterIsInstance<CanvasRelayMessage.Publish>().map { it.op.opId }
        assertEquals(listOf("n-1"), published, "the plugin op never reaches a host that would refuse the connection over it")
        assertEquals(listOf(CanvasPluginElementFixtures.place(2).opId), phone.delivery.queued(topic), "held, not dropped")
        assertNull(legacy.refusal)

        // The host is redeployed: the next connection says it reads them, and the op goes up.
        legacy.drop()
        run.join()
        phone.connect(host)
        runCurrent()
        assertEquals(listOf("n-1", CanvasPluginElementFixtures.place(2).opId), store.readAfter(topic, 0L).map { it.op.opId })
        assertEquals(emptyList(), phone.delivery.queued(topic))
    }

    @Test
    fun aBatchIsKeptWholeWhenItCanBeReadAndDroppedWhenNothingOfItCan() {
        val plugin = CanvasPluginElementFixtures.place(1)
        val readable = CanvasOp.BatchOp("b", "a", 1, listOf(note("n", 1)))
        assertEquals(readable, CanvasRelayFeatures.forPeer(readable, emptyList()))
        assertNull(CanvasRelayFeatures.forPeer(CanvasOp.BatchOp("b", "a", 1, listOf(plugin)), emptyList()))
        assertEquals(plugin, CanvasRelayFeatures.forPeer(plugin, CanvasRelayFeatures.SUPPORTED))
    }

    /**
     * A connection to a host built before plugin elements, as an app sees it: its Joined names no
     * features, and a frame it cannot decode would get the connection refused (recorded in [refusal]).
     */
    private class LegacyHostConnection private constructor() : CanvasRelayConnection {
        override val hostId: String = "host-1"
        val sent = mutableListOf<CanvasRelayMessage>()
        var refusal: String? = null
        private val inbox = Channel<CanvasRelayMessage?>(Channel.UNLIMITED)
        private lateinit var session: CanvasRelayHost.Session

        companion object {
            suspend fun to(host: CanvasRelayHost, origin: String): LegacyHostConnection =
                LegacyHostConnection().also { connection ->
                    connection.session = host.connect(origin) { connection.inbox.trySend(connection.asLegacy(it)) }
                }
        }

        private fun asLegacy(message: CanvasRelayMessage): CanvasRelayMessage =
            if (message is CanvasRelayMessage.Joined) message.copy(features = emptyList()) else message

        override suspend fun send(message: CanvasRelayMessage) {
            if (message is CanvasRelayMessage.Publish && CanvasRelayFeatures.required(message.op).isNotEmpty()) {
                refusal = "malformed frame: unknown op type"
            }
            sent += message
            session.receive(message)
        }

        override suspend fun receive(): CanvasRelayMessage? = inbox.receive()

        suspend fun drop() {
            inbox.trySend(null)
            session.close()
        }
    }
}
