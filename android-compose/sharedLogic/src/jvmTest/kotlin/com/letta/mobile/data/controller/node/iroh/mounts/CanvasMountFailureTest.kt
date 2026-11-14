package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.hoststate.DurableHostFileHooks
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.TelemetryValue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FIRST = CanvasId("canvas-first")
private val NEXT = CanvasId("canvas-next")

/** A short deadline for tests that must expire. */
private val SHORT = testDeadline(300.milliseconds, 300.milliseconds)

/** One mutation route, how to seed its record and how a later resolve sees the record. */
private enum class Mutation(val route: CanvasMountRoute, val seeded: CanvasId?) {
    BIND(CanvasMountRoute.BIND, null),
    REPLACE(CanvasMountRoute.REPLACE, FIRST),
    SET_DEFAULT(CanvasMountRoute.SET_DEFAULT, FIRST),
    ;

    suspend fun seed(harness: MountHarness) {
        val canvas = seeded ?: return
        if (route == CanvasMountRoute.SET_DEFAULT) harness.store().setDefault(AGENT_A, canvas)
        else harness.store().bindIfAbsent(AGENT_A, CONV_A, canvas)
    }
}

private fun unavailable(reason: String) = CanvasMountWire.UNAVAILABLE to reason

private fun Reply.unavailability() = status to reason

/** Registers the test canvases on [harness]'s host, owned by agent A. */
private suspend fun granted(harness: MountHarness): MountHarness = harness.apply {
    canvas(FIRST, AGENT_A)
    canvas(NEXT, AGENT_A)
}

class CanvasMountFailureTest {
    @Test
    fun aCorruptRecordIsUnavailableWithoutPathOrMessageInResponseOrTelemetry() = runBlocking {
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val harness = granted(MountHarness())
            harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, FIRST), PEER)
            val record = Files.list(harness.root.resolve("canvas-mounts")).use { files ->
                files.filter { it.isBindingRecord() }.toList().single()
            }
            Files.writeString(record, "{\"corrupt\": \"${harness.root}\"}")
            val read = harness.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
            val write = harness.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
            assertEquals(unavailable(CanvasMountWire.REASON_STORAGE), read.unavailability())
            assertEquals(unavailable(CanvasMountWire.REASON_STORAGE), write.unavailability())
            assertNoHostDetail(harness.root, harness.responses + recorder.snapshot().map { it.toLine() + it.attrs.toString() })
            val failures = recorder.snapshot().filter { it.name == "route.outcome" }
                .mapNotNull { it.attrs["failure"] }.filter { it != TelemetryValue.Null }
            assertEquals(2, failures.size)
        }
    }

    @Test
    fun aFailureAfterTheMoveIsOutcomeUnknownAndResolveThenReturnsTheCommittedCanvas() = runBlocking {
        Mutation.entries.forEach { mutation ->
            val attempt = failAtMove(mutation) { failure -> DurableHostFileHooks(afterMove = { throw failure }) }
            assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, attempt.reply.status, "$mutation")
            assertEquals(CanvasMountWire.FOUND to NEXT.value, attempt.resolved.status to attempt.resolved.canvas, "$mutation")
            assertNoHostDetail(attempt.root, attempt.failing.responses)
        }
    }

    @Test
    fun aFailureBeforeTheMoveIsUnavailableAndChangesNothing() = runBlocking {
        Mutation.entries.forEach { mutation ->
            val attempt = failAtMove(mutation) { failure -> DurableHostFileHooks(beforeMove = { throw failure }) }
            assertEquals(unavailable(CanvasMountWire.REASON_STORAGE), attempt.reply.unavailability(), "$mutation")
            assertEquals(mutation.seeded?.value, attempt.resolved.canvas, "$mutation")
        }
    }

    /** One [mutation] of a seeded record whose store fails as [hooks] script, and what a later resolve then sees. */
    private class MoveAttempt(val root: Path, val failing: MountHarness, val reply: Reply, val resolved: Reply)

    private suspend fun failAtMove(mutation: Mutation, hooks: (IOException) -> DurableHostFileHooks): MoveAttempt {
        val root = Files.createTempDirectory("canvas-mount-move")
        mutation.seed(MountHarness(root))
        val failing = granted(MountHarness(root, HarnessOptions(hooks = hooks(IOException("$root/secret")))))
        val reply = failing.call(mutation.route, paramsFor(mutation.route, CONV_A, NEXT), PEER)
        val resolved = granted(MountHarness(root)).call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        return MoveAttempt(root, failing, reply, resolved)
    }

    @Test
    fun aHungStripeHolderTimesTheCallOutBeforeItsCommitPoint() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-hung-lock")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hooks = DurableHostFileHooks(
            beforeMove = {
                entered.countDown()
                release.await()
            },
        )
        val holder = granted(MountHarness(root, HarnessOptions(hooks = hooks)))
        val held = async(Dispatchers.Default) { holder.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, FIRST), PEER) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val waiter = granted(MountHarness(root, HarnessOptions(deadline = SHORT)))
            val timedOut = waiter.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
            assertEquals(unavailable(CanvasMountWire.REASON_TIMEOUT_BEFORE_COMMIT), timedOut.unavailability())
            release.countDown()
            assertEquals(CanvasMountWire.BOUND to FIRST.value, held.await().let { it.status to it.canvas })
            assertEquals(FIRST, waiter.store().readBinding(AGENT_A, CONV_A))
        } finally {
            // A failed assertion must not leave the hook blocked or the held call running.
            release.countDown()
            held.cancelAndJoin()
        }
    }

    @Test
    fun aDeadlineAfterTheMoveIsOutcomeUnknownAndResolveThenReturnsTheCommittedCanvas() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-deadline-after")
        val release = CountDownLatch(1)
        val hooks = DurableHostFileHooks(afterMove = { release.await() })
        val stuck = granted(MountHarness(root, HarnessOptions(hooks = hooks, deadline = SHORT)))
        try {
            val reply = stuck.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
            assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, reply.status)
        } finally {
            release.countDown()
        }
        val resolved = granted(MountHarness(root)).call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(NEXT.value, resolved.canvas)
    }

    @Test
    fun aDeadlineWhileCheckingTheBindWinnerIsOutcomeUnknownBecauseTheBindCommitted() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-deadline-winner")
        val harness = granted(MountHarness(root, HarnessOptions(deadline = SHORT)))
        // Lookup 1 authorizes the proposal; lookup 2, the winner check after storage, never answers.
        harness.canvases.hangFromCall = 2
        val reply = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, reply.status)
        assertEquals(NEXT, harness.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun aDeadlineWhileAuthorizingIsUnavailableAndStoresNothing() = runBlocking {
        val harness = granted(MountHarness(options = HarnessOptions(deadline = SHORT)))
        harness.directory.hang = true
        val reply = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(unavailable(CanvasMountWire.REASON_TIMEOUT_BEFORE_COMMIT), reply.unavailability())
        harness.directory.hang = false
        assertNull(harness.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun failedLookupsAreUnavailableOrDeniedBeforeStorageAndOutcomeUnknownAfterABind() = runBlocking {
        val secret = IllegalStateException("canvas directory at /srv/secret/host-canvases.json")
        val directoryDown = granted(MountHarness()).apply { directory.failure = secret }
        val grantsDown = granted(MountHarness()).apply { canvases.failure = secret }
        // Lookup 1 authorizes the proposal; lookup 2, the winner check after the bind committed, fails.
        val winnerCheckDown = granted(MountHarness()).apply {
            canvases.failure = secret
            canvases.failFromCall = 2
        }
        val read = directoryDown.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(unavailable(CanvasMountWire.REASON_AUTHORITY), read.unavailability())
        // Judging a proposed canvas, a failed lookup is the plain denial a missing or invisible canvas gets.
        val proposal = grantsDown.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(DENIED_RESPONSE, proposal.raw)
        assertNull(grantsDown.store().readBinding(AGENT_A, CONV_A))
        val committed = winnerCheckDown.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, committed.status)
        assertEquals(NEXT, winnerCheckDown.store().readBinding(AGENT_A, CONV_A))
        assertFalse((directoryDown.responses + grantsDown.responses + winnerCheckDown.responses).any { "secret" in it })
    }

    @Test
    fun aDeadlineThatNeverFiresLeavesNormalCallsUntouched() = runBlocking {
        val harness = granted(MountHarness(options = HarnessOptions(deadline = testDeadline(30.seconds, 1.seconds))))
        val reply = harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(CanvasMountWire.BOUND to true, reply.status to reply.won)
    }
}

private fun Path.isBindingRecord(): Boolean = fileName.toString().let { it.startsWith("binding-") && it.endsWith(".json") }

/** No response or event may carry the temp root (either separator) or a secret injected into an exception. */
internal fun assertNoHostDetail(root: Path, observed: List<String>) {
    val forbidden = listOf(root.toString(), root.toString().replace('\\', '/'), root.fileName.toString(), "secret")
    observed.forEach { line ->
        forbidden.forEach { marker -> assertFalse(marker in line, "leaked '$marker': $line") }
    }
}
