package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A proposal is bound to the connection its baseline was read on, even before the collector that follows the
 * connection has run: the link's identity is read synchronously, so nothing is sent from another connection's
 * revisions and no reply of another connection is applied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedShellStateBaselineTest {
    @Test
    fun aProposalReadsFirstWhenItsBaselineCameFromAnotherConnection() = runTest(StandardTestDispatcher()) {
        val link = IdentityLink()
        val shared = SharedShellState(link)
        link.answerNext(document(5, "from-A"))
        shared.refresh()
        assertTrue(shared.state.value.editable)
        // The connection changes and nothing has observed it yet (no run()): the baseline is another host's.
        link.identity.value = "B"
        link.answerNext(document(7, "from-B"))
        link.answerNext(document(8, "edited"))
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("a", JsonPrimitive("edited")))
        assertEquals(listOf(ShellStateWire.GET, ShellStateWire.GET, ShellStateWire.PROPOSE), link.methods)
        assertEquals(7L, link.proposedExpectedRevision, "the revision sent is the one read on connection B, not A's 5")
    }

    @Test
    fun aProposeReplyFromAnotherConnectionIsDroppedEvenBeforeTheCollectorRuns() = runTest(StandardTestDispatcher()) {
        val link = IdentityLink()
        val shared = SharedShellState(link)
        link.answerNext(document(5, "from-A"))
        shared.refresh()
        val reply = link.holdNext()
        var outcome: ShellStateEditOutcome? = null
        val edit = launch { outcome = shared.set("a", JsonPrimitive("edited")) }
        runCurrent()
        link.identity.value = "B"
        reply.complete(link.reply(document(9, "late-from-A")))
        edit.join()
        assertEquals(ShellStateEditOutcome.OFFLINE, outcome, "the connection changed under the edit: nothing is claimed applied")
        assertEquals(5L, shared.state.value.snapshot?.documentRevision, "the earlier connection's reply is not applied")
    }

    @Test
    fun aRefusalFromAnotherConnectionIsOfflineNotConflictOrRefusedAndLeavesTheDocumentAlone() = runTest(StandardTestDispatcher()) {
        listOf(ShellStateWire.REVISION_CONFLICT, ShellStateWire.TOO_LARGE, ShellStateWire.INVALID_REQUEST).forEach { refusal ->
            val link = IdentityLink()
            val shared = SharedShellState(link)
            link.answerNext(document(5, "from-A"))
            shared.refresh()
            val reply = link.holdNext()
            var outcome: ShellStateEditOutcome? = null
            val edit = launch { outcome = shared.set("a", JsonPrimitive("edited")) }
            runCurrent()
            link.identity.value = "B"
            reply.complete(ShellStateReply(false, error = refusal))
            edit.join()
            assertEquals(ShellStateEditOutcome.OFFLINE, outcome, "$refusal came from the connection that is gone")
            assertEquals(5L, shared.state.value.snapshot?.documentRevision, "the document is unchanged")
            assertEquals(listOf(ShellStateWire.GET, ShellStateWire.PROPOSE), link.methods, "$refusal triggered no refresh")
        }
    }

    @Test
    fun aPushIsBoundToTheConnectionItWasReceivedOnEvenIfTheLinkMovesBeforeItIsAdopted() = runTest(StandardTestDispatcher()) {
        val link = IdentityLink()
        val shared = SharedShellState(link)
        link.answerNext(document(5, "from-A"))
        val job = launch { shared.run() }
        runCurrent()
        assertEquals(5L, shared.state.value.snapshot?.documentRevision)
        // The push is taken in on connection A; the link moves to B at the instant the receipt is recorded, before
        // the push is adopted. B's own read has not happened yet.
        link.afterNextRead = { link.identity.value = "B" }
        link.frames.tryEmit(pushFrame(9, "pushed-on-A"))
        runCurrent()
        assertEquals(5L, shared.state.value.snapshot?.documentRevision, "A's push is not adopted as B's document")
        job.cancel()
    }

    @Test
    fun aDocumentAdmittedOnAIsRecordedAsAEvenIfTheLinkMovesToBBeforeTheProvenanceIsWritten() = runTest(StandardTestDispatcher()) {
        val link = IdentityLink()
        val shared = SharedShellState(link)
        val read = link.holdNext()
        val refreshing = launch { shared.refresh() }
        runCurrent()
        // The read of A is in flight. When its reply is admitted (the one read of the link's identity the check
        // makes), the link moves to B: anything that reads the identity again after that would see B.
        link.afterNextRead = { link.identity.value = "B" }
        read.complete(link.reply(document(5, "from-A")))
        refreshing.join()
        assertEquals(5L, shared.state.value.snapshot?.documentRevision, "A's document was admitted")

        link.answerNext(document(7, "from-B"))
        link.answerNext(document(8, "edited"))
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("a", JsonPrimitive("edited")))
        assertEquals(
            listOf(ShellStateWire.GET, ShellStateWire.GET, ShellStateWire.PROPOSE), link.methods,
            "A's document is not B's baseline, so the edit reads B first",
        )
        assertEquals(7L, link.proposedExpectedRevision, "the revision sent is B's, never A's 5")
    }

    private fun pushFrame(revision: Long, value: String): JsonObject {
        val body = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), document(revision, value)) as JsonObject
        return JsonObject(body + ("type" to JsonPrimitive(ShellStateWire.FRAME_TYPE)))
    }

    private fun document(revision: Long, value: String) = ShellStateSnapshot(
        1, revision, mapOf("a" to ShellStateEntry(JsonPrimitive(value), revision, ShellStateActor.User("endpoint-b"), 1)),
    )

    /** A host whose connection identity is read synchronously and whose answers the test queues in order. */
    private class IdentityLink : ShellStateLink {
        val identity = MutableStateFlow<String?>("A")
        val methods = mutableListOf<String>()
        var proposedExpectedRevision: Long? = null
        private val answers = ArrayDeque<CompletableDeferred<ShellStateReply>>()

        override val hostCapabilities: Set<String> get() = if (currentConnection != null) setOf(ShellStateWire.CAPABILITY) else emptySet()
        override val currentConnection: String?
            get() = identity.value.also { afterNextRead?.invoke().also { afterNextRead = null } }
        override val connection: Flow<String?> get() = identity
        val frames = kotlinx.coroutines.flow.MutableSharedFlow<JsonObject>(extraBufferCapacity = 4)
        override val pushes: Flow<JsonObject> = frames

        /** Runs once, right after the next read of [currentConnection]: a connection change at a chosen instant. */
        var afterNextRead: (() -> Unit)? = null

        fun reply(document: ShellStateSnapshot) =
            ShellStateReply(true, LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), document))

        fun answerNext(document: ShellStateSnapshot) = answers.addLast(CompletableDeferred(reply(document)))

        fun holdNext(): CompletableDeferred<ShellStateReply> = CompletableDeferred<ShellStateReply>().also { answers.addLast(it) }

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            methods += method
            if (method == ShellStateWire.PROPOSE) {
                proposedExpectedRevision = (params.getValue(ShellStateWire.CHANGES) as kotlinx.serialization.json.JsonArray)
                    .let { (it[0] as JsonObject).getValue(ShellStateWire.EXPECTED_REVISION) as JsonPrimitive }.content.toLong()
            }
            return answers.removeFirst().await()
        }
    }
}
