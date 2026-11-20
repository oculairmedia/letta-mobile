package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Real threads: a push and a read reply racing on a fresh connection never let the older revision win. */
class SharedShellStateRaceTest {
    @Test
    fun aPushAndAStaleReadReplyRacingOnAFreshConnectionNeverLetTheOlderRevisionWin() = runBlocking {
        repeat(ROUNDS) { round ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val link = RacingLink()
            val shared = SharedShellState(link)
            scope.launch { shared.run() }
            // A push only follows a connect: wait until the push collector is subscribed and this connection's read is in flight.
            withTimeout(TIMEOUT_MS) {
                link.pushes.subscriptionCount.first { it > 0 }
                link.readStarted.await()
            }
            val pushed = scope.launch {
                link.pushes.emit(frame(NEWER))
                // Unbuffered: this second emit only returns once the collector finished handling the first push.
                link.pushes.emit(frame(IGNORED))
            }
            link.releaseReply()
            pushed.join()
            withTimeout(TIMEOUT_MS) { shared.state.first { it.live } }
            assertEquals(NEWER, shared.state.value.snapshot?.documentRevision, "round $round")
            scope.cancel()
        }
    }

    @Test
    fun pushesRacingARefusedWriteNeverFlipTheDocumentBackToLive() = runBlocking {
        repeat(STRESS_ROUNDS) { round ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val link = RefusingLink()
            val shared = SharedShellState(link)
            scope.launch { shared.run() }
            withTimeout(TIMEOUT_MS) { shared.state.first { it.live } }
            val write = scope.launch { shared.set("a", JsonPrimitive("x")) }
            val pushes = scope.launch { (FIRST_PUSH..FIRST_PUSH + PUSHES).forEach { link.pushes.emit(frame(it)) } }
            link.releaseRefusal()
            write.join()
            pushes.join()
            assertEquals(false, shared.state.value.live, "round $round: only a successful read or write may set live again")
            scope.cancel()
        }
    }

    /** A connected host that reads fine and refuses the write once released. */
    private inner class RefusingLink : ShellStateLink {
        private val release = CompletableDeferred<Unit>()
        override val pushes = MutableSharedFlow<JsonObject>()
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        override val isConnected: Boolean = true
        override val connection: Flow<String?> = flowOf("session")

        fun releaseRefusal() = release.complete(Unit)

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            if (method == ShellStateWire.GET) return ShellStateReply(true, encoded(1))
            release.await()
            return ShellStateReply(false, error = ShellStateWire.TOO_LARGE)
        }
    }

    private fun frame(revision: Long): JsonObject =
        JsonObject(encoded(revision) + ("type" to JsonPrimitive(ShellStateWire.FRAME_TYPE)))

    private fun encoded(revision: Long): JsonObject =
        LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), snapshot(revision)) as JsonObject

    private fun snapshot(revision: Long) = ShellStateSnapshot(
        1, revision, mapOf("a" to ShellStateEntry(JsonPrimitive("v$revision"), revision, ShellStateActor.User("endpoint-b"), 1)),
    )

    /** A connected host whose `shell.state.get` answers the stale revision once released. */
    private inner class RacingLink : ShellStateLink {
        private val release = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        override val pushes = MutableSharedFlow<JsonObject>()
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        override val isConnected: Boolean = true
        override val connection: Flow<String?> = flowOf("session")

        fun releaseReply() = release.complete(Unit)

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            readStarted.complete(Unit)
            release.await()
            return ShellStateReply(true, encoded(STALE))
        }
    }

    private companion object {
        const val ROUNDS = 3000
        const val STRESS_ROUNDS = 1500
        const val FIRST_PUSH = 5L
        const val PUSHES = 40L
        const val TIMEOUT_MS = 5_000L
        const val STALE = 2L
        const val NEWER = 3L
        const val IGNORED = 1L
    }
}
