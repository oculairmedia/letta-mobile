package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.hoststate.DurableHostFileHooks
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val host = HostId("host-one")
private val agent = AgentId("agent-one")
private val conversation = ConversationId("conversation-one")
private val previous = CanvasId("previous-board")
private val next = CanvasId("next-board")

/** The secret an injected failure carries; it must never reach a facade result. */
private const val SECRET = "secret-injected-path-detail"

/** One raw store mutation, its starting state, and how to read its record back. */
private enum class StoreMutation(val before: CanvasId?) {
    BIND(null) {
        override suspend fun run(store: FileCanvasMountStore) = store.bindIfAbsent(agent, conversation, next)
        override suspend fun read(store: FileCanvasMountStore) = store.readBinding(agent, conversation)
        override suspend fun facade(mounts: AuthorizedCanvasMounts) =
            mounts.bindIfAbsent(bindingRequest(agent.value, conversation.value), next)
    },
    REPLACE(previous) {
        override suspend fun run(store: FileCanvasMountStore) = store.replaceBinding(agent, conversation, next)
        override suspend fun read(store: FileCanvasMountStore) = store.readBinding(agent, conversation)
        override suspend fun facade(mounts: AuthorizedCanvasMounts) =
            mounts.replaceBinding(bindingRequest(agent.value, conversation.value), next)
    },
    SET_DEFAULT(previous) {
        override suspend fun run(store: FileCanvasMountStore) = store.setDefault(agent, next)
        override suspend fun read(store: FileCanvasMountStore) = store.readDefault(agent)
        override suspend fun facade(mounts: AuthorizedCanvasMounts) = mounts.setDefault(defaultRequest(agent.value), next)
    },
    ;

    abstract suspend fun run(store: FileCanvasMountStore): CanvasMountMutation
    abstract suspend fun read(store: FileCanvasMountStore): CanvasId?
    abstract suspend fun facade(mounts: AuthorizedCanvasMounts): CanvasMountResult

    /** A fresh root holding [before] under this mutation's key. */
    suspend fun seededRoot(): Path {
        val root = Files.createTempDirectory("mount-commit-point")
        before?.let { seed(FileCanvasMountStore(root, host), it) }
        return root
    }

    private suspend fun seed(store: FileCanvasMountStore, canvas: CanvasId) {
        store.bindIfAbsent(agent, conversation, canvas)
        store.setDefault(agent, canvas)
    }
}

/** Which side of the atomic move an injected event happens on. */
private enum class Side { BEFORE_MOVE, AFTER_MOVE }

private fun hooksAt(side: Side, event: () -> Unit): DurableHostFileHooks = when (side) {
    Side.BEFORE_MOVE -> DurableHostFileHooks(beforeMove = event)
    Side.AFTER_MOVE -> DurableHostFileHooks(afterMove = event)
}

private fun storeWith(root: Path, hooks: DurableHostFileHooks) =
    FileCanvasMountStore(root, host, HostDirectoryDurability::syncMount, hooks)

/** What a caller sees: the outcome's type and the record read back afterwards. */
private data class Observed(val outcome: KClass<*>?, val stored: CanvasId?)

/** One injected failure: where it happens, and what the caller and a read must then see. */
private data class FailureCase(val mutation: StoreMutation, val side: Side) {
    val expected: Observed
        get() = when (side) {
            Side.BEFORE_MOVE -> Observed(CanvasMountMutation.NotApplied::class, mutation.before)
            Side.AFTER_MOVE -> Observed(CanvasMountMutation.OutcomeUnknown::class, next)
        }
}

/** One cancellation: the outcome a cancelled caller still receives, and the record afterwards. */
private data class CancellationCase(val mutation: StoreMutation, val side: Side) {
    val expected: Pair<CanvasMountMutation?, CanvasId?>
        get() = when (side) {
            Side.BEFORE_MOVE -> null to mutation.before
            Side.AFTER_MOVE -> CanvasMountMutation.Applied(next, written = true) to next
        }
}

/** What a facade caller must see for a failure on each side of the commit point. */
private fun facadeExpected(side: Side): CanvasMountResult = when (side) {
    Side.BEFORE_MOVE -> CanvasMountResult.StorageFailure(CanvasMountFailure.IO_FAILURE)
    Side.AFTER_MOVE -> CanvasMountResult.OutcomeUnknown
}

private fun failureCases() = StoreMutation.entries.flatMap { mutation -> Side.entries.map { FailureCase(mutation, it) } }

private fun cancellationCases() = StoreMutation.entries.flatMap { mutation -> Side.entries.map { CancellationCase(mutation, it) } }

/** F4: a mutation's outcome distinguishes "not applied" from "may have been applied", at the atomic move. */
class FileCanvasMountCommitPointTest {
    @Test
    fun anInjectedFailureIsNotAppliedBeforeTheMoveAndUnknownAfterIt() = runBlocking {
        for (case in failureCases()) {
            val root = case.mutation.seededRoot()
            val failing = storeWith(root, hooksAt(case.side) { throw IOException(SECRET) })
            val outcome = case.mutation.run(failing)
            val stored = case.mutation.read(FileCanvasMountStore(root, host))
            assertEquals(case.expected, Observed(outcome::class, stored), "$case")
            assertTemporaryFilesRemoved(root)
        }
    }

    @Test
    fun cancellationBeforeTheMovePropagatesAndAfterItStillCompletes() = runBlocking {
        for (case in cancellationCases()) {
            val root = case.mutation.seededRoot()
            var outcome: CanvasMountMutation? = null
            lateinit var job: Job
            val store = storeWith(root, hooksAt(case.side) { job.cancel() })
            job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { outcome = case.mutation.run(store) }
            job.start()
            job.join()
            assertTrue(job.isCancelled, "$case")
            assertEquals(case.expected, outcome to case.mutation.read(FileCanvasMountStore(root, host)), "$case")
        }
    }

    @Test
    fun theFacadeReportsUnknownWithoutPathOrExceptionTextAndFailureBeforeTheMoveAsStorageFailure() = runBlocking {
        for (case in failureCases()) {
            val root = case.mutation.seededRoot()
            val failing = storeWith(root, hooksAt(case.side) { throw IOException("$SECRET $root") })
            val result = case.mutation.facade(AuthorizedCanvasMounts(failing, TableAuthority(Answer.ALLOW)))
            assertEquals(facadeExpected(case.side), result, "$case")
            assertEquals(case.expected.stored, case.mutation.read(FileCanvasMountStore(root, host)), "$case")
            listOf(SECRET, root.toString(), "canvas-mounts", "IOException").forEach { leaked ->
                assertFalse(result.toString().contains(leaked), result.toString())
            }
        }
    }

    @Test
    fun theUnknownResultPrintsOnlyItsName() {
        assertEquals("OutcomeUnknown", CanvasMountResult.OutcomeUnknown.toString())
    }

    private fun assertTemporaryFilesRemoved(root: Path) {
        val leftovers = Files.walk(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
        assertEquals(emptyList<Path>(), leftovers)
    }
}
