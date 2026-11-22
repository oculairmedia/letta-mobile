package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.ShellStateSnapshotCache
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
        within { a.listeners.first { it > 0 } }

        a.deliver(frameSettingTheme(AppTheme.DARK, revision = 5))
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
        val mirror = RecordingCache()
        val shell = SharedShellState(link, mirror)
        scope.launch { shell.run() }
        within { shell.state.first { it.editable } }
        link.connectionNow = SECOND
        within { link.reads.first { it >= 2 } }
        within { shell.state.first { it.live } }
        within { frames.subscriptionCount.first { it > 0 } }

        // A frame A had already delivered arrives while B is the connection that is up; B's own frame follows it.
        // Frames are decided in the order they arrive, so once B's frame is shown A's has been decided already. Adopting
        // a document mirrors it, so the mirror says whether A's frame was ever applied.
        frames.emit(ShellStatePush(frameSettingTheme(AppTheme.DARK, revision = 99), FIRST))
        frames.emit(ShellStatePush(frameSettingTheme(AppTheme.LIGHT, revision = 100), SECOND))
        within { shell.state.first { it.entries[AppearanceKeys.THEME]?.value == JsonPrimitive(AppTheme.LIGHT.name) } }

        val applied = mirror.themes()
        assertTrue(applied.none { it == JsonPrimitive(AppTheme.DARK.name) }, "A's late frame was applied: $applied")
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

    private fun frameSettingTheme(theme: AppTheme, revision: Long): JsonObject {
        val entry = ShellStateEntry(JsonPrimitive(theme.name), revision, ShellStateActor.User(PUSHER.id), 1)
        val document = ShellStateSnapshot(1, revision, mapOf(AppearanceKeys.THEME to entry))
        val body = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), document) as JsonObject
        return buildJsonObject {
            put("v", 1)
            put("type", ShellStateWire.FRAME_TYPE)
            body.forEach { (key, value) -> put(key, value) }
        }
    }

    /** The document mirror that remembers every document it was asked to keep, in order. */
    private class RecordingCache : ShellStateSnapshotCache {
        private val saved = java.util.concurrent.CopyOnWriteArrayList<ShellStateSnapshot>()

        /** The theme each mirrored document held, in the order they were mirrored. */
        fun themes() = saved.map { it.entries[AppearanceKeys.THEME]?.value }

        override fun load(): ShellStateSnapshot? = null

        override fun save(snapshot: ShellStateSnapshot) {
            saved += snapshot
        }

        override fun clear() = saved.clear()
    }

    /** A link to a host that answers every read with an empty document; pushes are whatever the test emits. */
    private class ScriptedLink(private val frames: Flow<ShellStatePush>) : ShellStateLink {
        private val up = MutableStateFlow<String?>(FIRST)
        var connectionNow: String?
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
        val reads = MutableStateFlow(0)

        override suspend fun call(method: String, params: JsonObject): ShellStateReply = emptyDocument()

        override suspend fun callOn(connection: String?, method: String, params: JsonObject): ShellStateReply = emptyDocument()

        private fun emptyDocument() = ShellStateReply(
            success = true,
            result = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), ShellStateSnapshot(1, 1, emptyMap())),
        ).also { reads.update { it + 1 } }
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

        /** The connections a link reports: A's, then B's after the swap. */
        const val FIRST = "1:host#1"
        const val SECOND = "2:host#1"
        val PUSHER = Endpoint("another-device")
    }
}
