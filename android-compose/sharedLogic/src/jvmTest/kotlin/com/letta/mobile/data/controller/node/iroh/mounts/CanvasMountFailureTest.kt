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

private suspend fun granted(harness: MountHarness): MountHarness = harness.apply {
    grant(PEER, FIRST, CanvasMountGrant.MOUNT)
    grant(PEER, NEXT, CanvasMountGrant.MOUNT)
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
            val root = Files.createTempDirectory("canvas-mount-after-move")
            mutation.seed(MountHarness(root))
            val hooks = DurableHostFileHooks(afterMove = { throw IOException("$root/secret") })
            val failing = granted(MountHarness(root, HarnessOptions(hooks = hooks)))
            val reply = failing.call(mutation.route, paramsFor(mutation.route, CONV_A, NEXT), PEER)
            assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, reply.status, "$mutation")
            val resolved = granted(MountHarness(root)).call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
            assertEquals(CanvasMountWire.FOUND to NEXT.value, resolved.status to resolved.canvas, "$mutation")
            assertNoHostDetail(root, failing.responses)
        }
    }

    @Test
    fun aFailureBeforeTheMoveIsUnavailableAndChangesNothing() = runBlocking {
        Mutation.entries.forEach { mutation ->
            val root = Files.createTempDirectory("canvas-mount-before-move")
            mutation.seed(MountHarness(root))
            val hooks = DurableHostFileHooks(beforeMove = { throw IOException("$root/secret") })
            val failing = granted(MountHarness(root, HarnessOptions(hooks = hooks)))
            val reply = failing.call(mutation.route, paramsFor(mutation.route, CONV_A, NEXT), PEER)
            assertEquals(unavailable(CanvasMountWire.REASON_STORAGE), reply.unavailability(), "$mutation")
            val resolved = granted(MountHarness(root)).call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
            assertEquals(mutation.seeded?.value, resolved.canvas, "$mutation")
        }
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
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val waiter = granted(MountHarness(root, HarnessOptions(deadline = SHORT)))
        val timedOut = waiter.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(unavailable(CanvasMountWire.REASON_TIMEOUT_BEFORE_COMMIT), timedOut.unavailability())
        release.countDown()
        assertEquals(CanvasMountWire.BOUND to FIRST.value, held.await().let { it.status to it.canvas })
        assertEquals(FIRST, waiter.store().readBinding(AGENT_A, CONV_A))
    }

    @Test
    fun aDeadlineAfterTheMoveIsOutcomeUnknownAndResolveThenReturnsTheCommittedCanvas() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-deadline-after")
        val release = CountDownLatch(1)
        val hooks = DurableHostFileHooks(afterMove = { release.await() })
        val stuck = granted(MountHarness(root, HarnessOptions(hooks = hooks, deadline = SHORT)))
        val reply = stuck.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(CanvasMountWire.OUTCOME_UNKNOWN, reply.status)
        release.countDown()
        val resolved = granted(MountHarness(root)).call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(NEXT.value, resolved.canvas)
    }

    @Test
    fun aDeadlineWhileCheckingTheBindWinnerIsOutcomeUnknownBecauseTheBindCommitted() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-deadline-winner")
        val harness = granted(MountHarness(root, HarnessOptions(deadline = SHORT)))
        // Lookup 1 authorizes the proposal; lookup 2, the winner check after storage, never answers.
        harness.grants.hangFromCall = 2
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
    fun failedAuthorityLookupsAreUnavailableBeforeStorageAndOutcomeUnknownAfterABind() = runBlocking {
        val secret = IllegalStateException("grant file at /srv/secret/canvas-grants.json")
        val directoryDown = granted(MountHarness()).apply { directory.failure = secret }
        val grantsDown = granted(MountHarness()).apply { grants.failure = secret }
        // Lookup 1 authorizes the proposal; lookup 2, the winner check after the bind committed, fails.
        val winnerCheckDown = granted(MountHarness()).apply {
            grants.failure = secret
            grants.failFromCall = 2
        }
        val read = directoryDown.call(CanvasMountRoute.RESOLVE, bindingParams(AGENT_A, CONV_A), PEER)
        assertEquals(unavailable(CanvasMountWire.REASON_AUTHORITY), read.unavailability())
        val proposal = grantsDown.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, NEXT), PEER)
        assertEquals(unavailable(CanvasMountWire.REASON_AUTHORITY), proposal.unavailability())
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
