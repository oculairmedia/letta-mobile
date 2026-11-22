package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStatePush
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A client whose transport is replaced as a whole ([channelShellStateLink] over a state flow of transports): nothing
 * that belongs to the transport it left is applied after the swap, and nothing is proposed over the new connection
 * on a baseline the old one supplied.
 */
class FollowingLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun aPushIsTaggedWithTheConnectionThatDeliveredItNotTheOneUpWhenItIsRead() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val a = host.transport(Endpoint("a"))
        val b = host.transport(Endpoint("b"))
        val transports = MutableStateFlow<IChannelTransport?>(a)
        val link = channelShellStateLink(transports)
        val received = MutableSharedFlow<ShellStatePush>(replay = 8)
        scope.launch { link.originatedPushes.collect { received.emit(it) } }
        val connectionOfA = link.currentConnection
        within { while (!a.isListenedTo) delay(POLL_MS) }

        a.deliver(frameSettingTheme("DARK", revision = 5))
        within { received.first() }
        transports.value = b

        val tagged = received.first()
        assertEquals(connectionOfA, tagged.connection, "tagged with A's connection")
        assertNotEquals(link.currentConnection, tagged.connection, "which is no longer the connection that is up")
    }

    @Test
    fun aFrameFromTheTransportThatWasLeftIsNotAppliedAfterTheSwap() = runBlocking<Unit> {
        val frames = MutableSharedFlow<ShellStatePush>(extraBufferCapacity = 4)
        val link = ScriptedLink(frames)
        val shell = SharedShellState(link)
        scope.launch { shell.run() }
        within { shell.state.first { it.editable } }

        // A frame A had already delivered arrives while B is the connection that is up.
        link.connectionNow = "2:host#1"
        within { while (link.reads < 2) delay(POLL_MS) }
        within { shell.state.first { it.live } }
        frames.emit(ShellStatePush(frameSettingTheme("DARK", revision = 99), connection = "1:host#1"))
        delay(SETTLE_MS)

        assertEquals(null, shell.state.value.entries[AppearanceKeys.THEME]?.value, "the late frame of A is not B's document")
        frames.emit(ShellStatePush(frameSettingTheme("LIGHT", revision = 100), connection = "2:host#1"))
        within { shell.state.first { it.entries[AppearanceKeys.THEME]?.value == JsonPrimitive("LIGHT") } }
    }

    @Test
    fun aProposalIsNotSentOverANewConnectionOnABaselineTheOldOneSupplied() = runBlocking<Unit> {
        val hostA = RouterShellStateHost()
        val hostB = RouterShellStateHost()
        val transports = MutableStateFlow<IChannelTransport?>(hostA.transport(Endpoint("desktop")))
        val swapping = SwapsBeforeCalling(channelShellStateLink(transports)) { transports.value = hostB.transport(Endpoint("desktop")) }
        val shell = SharedShellState(swapping)
        scope.launch { shell.run() }
        within { shell.state.first { it.editable } }
        swapping.armed = true

        val outcome = shell.set(AppearanceKeys.THEME, JsonPrimitive("DARK"))

        assertEquals(ShellStateEditOutcome.OFFLINE, outcome, "refused: the connection changed under the proposal")
        assertTrue(hostB.proposals.isEmpty(), "the new host was sent nothing: ${hostB.proposals}")
        assertTrue(hostA.proposals.isEmpty(), "and the old one neither")
    }

    private fun frameSettingTheme(theme: String, revision: Long): JsonObject {
        val document = ShellStateSnapshot(
            1, revision, mapOf(AppearanceKeys.THEME to ShellStateEntry(JsonPrimitive(theme), revision, ShellStateActor.User("x"), 1)),
        )
        val body = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), document) as JsonObject
        return buildJsonObject {
            put("v", 1)
            put("type", ShellStateWire.FRAME_TYPE)
            body.forEach { (key, value) -> put(key, value) }
        }
    }

    /** A link to a host that answers every read with an empty document; pushes are whatever the test emits. */
    private inner class ScriptedLink(private val frames: Flow<ShellStatePush>) : ShellStateLink {
        private val up = MutableStateFlow("1:host#1")
        var connectionNow: String
            get() = up.value
            set(value) {
                up.value = value
            }
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        override val currentConnection: String? get() = up.value
        override val connection: Flow<String?> get() = up
        override val pushes: Flow<JsonObject> get() = kotlinx.coroutines.flow.flow { frames.collect { emit(it.frame) } }
        override val originatedPushes: Flow<ShellStatePush> get() = frames

        /** How many reads the host has answered: one per connection that came up. */
        @Volatile var reads = 0
            private set

        override suspend fun call(method: String, params: JsonObject): ShellStateReply = emptyDocument()

        override suspend fun callOn(connection: String?, method: String, params: JsonObject): ShellStateReply = emptyDocument()

        private fun emptyDocument() = ShellStateReply(
            success = true,
            result = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), ShellStateSnapshot(1, 1, emptyMap())),
        ).also { reads += 1 }
    }

    /** Swaps the transport once, just before the first proposal goes out: the window between a baseline and its proposal. */
    private class SwapsBeforeCalling(private val inner: ShellStateLink, private val swap: () -> Unit) : ShellStateLink by inner {
        var armed = false

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            swapFirstProposal(method)
            return inner.call(method, params)
        }

        override suspend fun callOn(connection: String?, method: String, params: JsonObject): ShellStateReply {
            swapFirstProposal(method)
            return inner.callOn(connection, method, params)
        }

        private fun swapFirstProposal(method: String) {
            if (armed && method == ShellStateWire.PROPOSE) {
                armed = false
                swap()
            }
        }
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 10L
    }
}
