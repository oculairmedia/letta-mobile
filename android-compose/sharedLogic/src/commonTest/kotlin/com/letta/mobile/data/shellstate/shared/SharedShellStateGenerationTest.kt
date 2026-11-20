package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interleavings of replies, pushes and connection transitions, one per way they can cross. Every read the
 * host would answer is scripted: the test decides which connection's reply lands when.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedShellStateGenerationTest {
    @Test
    fun aReconnectDuringAPendingReadIsObservedAndReadsAgain() = runTest(UnconfinedTestDispatcher()) {
        val link = ScriptedLink()
        val first = link.expectRead()
        val second = link.expectRead()
        val shared = SharedShellState(link)
        val job = launch { shared.run() }
        link.connected.value = false
        link.connected.value = true
        second.complete(link.document(1))
        first.complete(link.document(100))
        assertEquals(1L, shared.state.value.snapshot?.documentRevision, "the earlier connection's read is dropped")
        assertTrue(shared.state.value.live)
        assertEquals(2, link.reads, "the reconnect was not conflated into the first connection")
        job.cancel()
    }

    @Test
    fun aReplyFromAnEarlierConnectionNeverReplacesTheCurrentDocument() = runTest(UnconfinedTestDispatcher()) {
        val link = ScriptedLink()
        val shared = SharedShellState(link)
        val earlier = link.expectRead()
        val pending = launch { shared.refresh() }
        link.connected.value = false
        shared.refresh()
        link.connected.value = true
        val current = link.expectRead()
        val reading = launch { shared.refresh() }
        current.complete(link.document(1))
        reading.join()
        earlier.complete(link.document(100))
        pending.join()
        assertEquals(1L, shared.state.value.snapshot?.documentRevision)
        assertTrue(shared.state.value.live)
    }

    @Test
    fun anOfflineRefreshResetsProvenanceSoTheNextConnectionIsNotOutrankedByAnOldRevision() = runTest(UnconfinedTestDispatcher()) {
        val link = ScriptedLink()
        val shared = SharedShellState(link)
        val read = link.expectRead()
        read.complete(link.document(10))
        shared.refresh()
        assertEquals(10L, shared.state.value.snapshot?.documentRevision)
        link.connected.value = false
        shared.refresh()
        assertEquals(10L, shared.state.value.snapshot?.documentRevision, "the offline document stays shown")
        link.connected.value = true
        val next = link.expectRead()
        next.complete(link.document(0))
        shared.refresh()
        assertEquals(0L, shared.state.value.snapshot?.documentRevision, "the new connection's document wins")
        assertTrue(shared.state.value.live)
    }

    @Test
    fun aFailureOfAnEarlierConnectionNeverTurnsTheCurrentOneStale() = runTest(UnconfinedTestDispatcher()) {
        val link = ScriptedLink()
        val shared = SharedShellState(link)
        val earlier = link.expectRead()
        val pending = launch { shared.refresh() }
        link.connected.value = false
        shared.refresh()
        link.connected.value = true
        val current = link.expectRead()
        current.complete(link.document(1))
        shared.refresh()
        assertTrue(shared.state.value.live)
        earlier.completeExceptionally(IllegalStateException("link dropped"))
        pending.join()
        assertTrue(shared.state.value.live, "a failure that belongs to the earlier connection changes nothing now")
        assertEquals(null, shared.state.value.error)
    }

    @Test
    fun aPushNeverTouchesLiveInEitherDirection() = runTest(UnconfinedTestDispatcher()) {
        val link = ScriptedLink()
        link.expectRead().complete(link.document(1))
        val shared = SharedShellState(link)
        val job = launch { shared.run() }
        assertTrue(shared.state.value.live)
        link.push(2)
        assertTrue(shared.state.value.live, "a push leaves a live document live")
        link.connected.value = false
        link.push(3)
        assertFalse(shared.state.value.live, "a push leaves a stale document stale")
        assertEquals(3L, shared.state.value.snapshot?.documentRevision)
        job.cancel()
    }

    /** A host whose reads are answered when the test says so, and whose capabilities vanish while disconnected. */
    private class ScriptedLink : ShellStateLink {
        val connected = MutableStateFlow(true)
        val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 8)
        private val scripted = ArrayDeque<CompletableDeferred<ShellStateReply>>()
        var reads = 0
            private set

        override val hostCapabilities: Set<String> get() = if (connected.value) setOf(ShellStateWire.CAPABILITY) else emptySet()
        override val isConnected: Boolean get() = connected.value
        override val connection: Flow<Boolean> get() = connected
        override val pushes: Flow<JsonObject> = frames

        /** The next read will wait on the returned deferred, which the test completes with a document or a failure. */
        fun expectRead(): CompletableDeferred<ShellStateReply> = CompletableDeferred<ShellStateReply>().also { scripted.addLast(it) }

        fun document(revision: Long) = ShellStateReply(true, encoded(revision))

        fun push(revision: Long) {
            frames.tryEmit(JsonObject(encoded(revision) + ("type" to JsonPrimitive(ShellStateWire.FRAME_TYPE))))
        }

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            reads += 1
            return scripted.removeFirst().await()
        }

        private fun encoded(revision: Long): JsonObject = LettaJson.persisted.encodeToJsonElement(
            ShellStateSnapshot.serializer(),
            ShellStateSnapshot(
                1, revision, mapOf("a" to ShellStateEntry(JsonPrimitive("v$revision"), revision, ShellStateActor.User("endpoint-b"), 1)),
            ),
        ) as JsonObject
    }
}
