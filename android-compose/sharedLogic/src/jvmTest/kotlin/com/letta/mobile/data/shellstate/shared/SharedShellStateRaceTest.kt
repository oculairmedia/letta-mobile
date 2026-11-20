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
            withTimeout(TIMEOUT_MS) { while (!link.collecting()) Thread.yield() }
            val pushed = scope.launch { link.pushes.emit(frame(NEWER)) }
            link.releaseReply()
            pushed.join()
            withTimeout(TIMEOUT_MS) { while (!shared.state.value.live) Thread.yield() }
            assertEquals(NEWER, shared.state.value.snapshot?.documentRevision, "round $round")
            scope.cancel()
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
        override val pushes = MutableSharedFlow<JsonObject>(extraBufferCapacity = 4)
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        override val isConnected: Boolean = true
        override val connection: Flow<Boolean> = flowOf(true)

        fun collecting(): Boolean = pushes.subscriptionCount.value > 0

        fun releaseReply() = release.complete(Unit)

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            release.await()
            return ShellStateReply(true, encoded(STALE))
        }
    }

    private companion object {
        const val ROUNDS = 3000
        const val TIMEOUT_MS = 5_000L
        const val STALE = 2L
        const val NEWER = 3L
    }
}
