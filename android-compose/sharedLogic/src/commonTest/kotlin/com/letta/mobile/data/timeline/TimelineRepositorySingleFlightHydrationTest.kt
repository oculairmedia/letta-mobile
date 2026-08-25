package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * letta-mobile-oznnh: TimelineRepository hydration must be single-flight per
 * conversation.
 *
 * The loop becomes visible in the registry BEFORE its first hydration
 * completes, so a concurrent same-conversation caller used to hit the fast
 * path with hasHydratedSuccessfully=false and start a DUPLICATE hydrate.
 * These tests prove concurrent callers now join one in-flight hydration,
 * different conversations stay independent, and failure permits retry.
 *
 * Uses runTest as a shell around a fully REAL-dispatcher scenario
 * (withContext(Dispatchers.Default)): the barriers are cross-thread
 * CompletableDeferreds and withTimeout must measure REAL time. Inside the
 * test scheduler, virtual time fast-forwards any timeout parked on a
 * real-thread signal; wasmJs additionally has no runBlocking, so this
 * wrapper is the only portable way to get real-time coordination.
 */
class TimelineRepositorySingleFlightHydrationTest {

    private data class HydrationTarget(
        val conversationId: String,
        val agentId: String? = null,
    )

    /**
     * One configurable fake covers every scenario: [failFirstCall] makes the
     * first hydration attempt throw; when gated, list calls park on
     * [releaseGate] so the test holds the in-flight flight open.
     */
    private class FakeHydrationTransport(
        private val failFirstCall: Boolean = false,
        private val gated: Boolean = false,
    ) : TimelineTransport by EmptyTimelineTransport {
        val listCalls = atomic(0)

        /** Completed when the first list call STARTS (owner claimed the flight). */
        val firstListStarted = CompletableDeferred<Unit>()
        val secondListStarted = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        fun releaseGate() {
            release.complete(Unit)
        }

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            val callNumber = listCalls.incrementAndGet()
            if (callNumber == 1) firstListStarted.complete(Unit)
            if (callNumber == 2) secondListStarted.complete(Unit)
            if (failFirstCall && callNumber == 1) throw RuntimeException("hydration boom")
            if (gated) release.await()
            return emptyList()
        }

    }

    private fun newRepo(
        transport: TimelineTransport,
        repositoryScope: CoroutineScope,
    ): TimelineRepository =
        // Stream subscribers OFF: they emit async Telemetry stragglers from
        // the IO dispatcher after the test returns, which pollutes sibling
        // tests that assert exact Telemetry snapshot counts
        // (TimelineStateDumpTest counts exactly 1 event). The single-flight
        // behavior under test doesn't involve the stream path.
        TimelineRepository(
            transport,
            NoOpPendingLocalStore,
            NoOpConversationCursorStore,
            repositoryScope = repositoryScope,
            startLoopStreamSubscribers = false,
        )

    private suspend fun TimelineRepository.getOrCreate(target: HydrationTarget): TimelineSyncLoop =
        getOrCreate(target.agentId, target.conversationId)

    @Test
    fun concurrent_same_conversation_callers_join_one_hydration() = runTest {
        withContext(Dispatchers.Default) {
            val transport = FakeHydrationTransport(gated = true)
            val repo = newRepo(transport, backgroundScope)
            try {
                // Caller A runs eagerly (Unconfined) until hydrate hops to the
                // IO dispatcher; by then it has REGISTERED the single flight
                // for conv-sf-1. Callers B..D then observe the un-hydrated
                // cached loop and must JOIN that flight (B/C same agent key,
                // D aliased via agentId).
                val target = HydrationTarget("conv-sf-1")
                val callerA = async(Dispatchers.Unconfined) { repo.getOrCreate(target) }
                val callerB = async(Dispatchers.Unconfined) { repo.getOrCreate(target) }
                val callerC = async(Dispatchers.Unconfined) { repo.getOrCreate(target) }
                val callerD = async(Dispatchers.Unconfined) { repo.getOrCreate(target.copy(agentId = "agent-x")) }

                // Deterministic barrier: wait until the owner's hydrate is
                // actually executing upstream (all callers already launched
                // eagerly), THEN release.
                withTimeout(10_000) { transport.firstListStarted.await() }
                transport.releaseGate()

                val loopA = callerA.await()
                val loopB = callerB.await()
                val loopC = callerC.await()
                val loopD = callerD.await()

                // Exactly ONE hydration API call for four concurrent callers.
                assertEquals(1, transport.listCalls.value)
                assertTrue(loopA.hasHydratedSuccessfully)
                assertSame(loopA, loopB)
                assertSame(loopA, loopC)
                assertSame(loopA, loopD)
            } finally {
                repo.clearAll()
            }
        }
    }

    @Test
    fun same_conversation_with_conflicting_agents_hydrates_both_loops() = runTest {
        withContext(Dispatchers.Default) {
            val transport = FakeHydrationTransport(gated = true)
            val repo = newRepo(transport, backgroundScope)
            try {
                val target = HydrationTarget("conv-scoped")
                val callerA = async(Dispatchers.Unconfined) { repo.getOrCreate(target.copy(agentId = "agent-a")) }
                val callerB = async(Dispatchers.Unconfined) { repo.getOrCreate(target.copy(agentId = "agent-b")) }

                withTimeout(10_000) {
                    transport.firstListStarted.await()
                    transport.secondListStarted.await()
                }
                transport.releaseGate()

                val loopA = callerA.await()
                val loopB = callerB.await()
                assertEquals(2, transport.listCalls.value)
                assertTrue(loopA.hasHydratedSuccessfully)
                assertTrue(loopB.hasHydratedSuccessfully)
                assertFalse(loopA === loopB)
            } finally {
                repo.clearAll()
            }
        }
    }

    @Test
    fun clear_during_hydration_does_not_bind_replacement_to_stale_flight() = runTest {
        withContext(Dispatchers.Default) {
            val transport = FakeHydrationTransport(gated = true)
            val repo = newRepo(transport, backgroundScope)
            try {
                val target = HydrationTarget("conv-clear")
                val original = async(Dispatchers.Unconfined) { repo.getOrCreate(target) }
                withTimeout(10_000) { transport.firstListStarted.await() }

                repo.clear("conv-clear")
                val replacement = async(Dispatchers.Unconfined) { repo.getOrCreate(target) }
                withTimeout(10_000) { transport.secondListStarted.await() }
                transport.releaseGate()

                val originalLoop = original.await()
                val replacementLoop = replacement.await()
                assertEquals(2, transport.listCalls.value)
                assertTrue(replacementLoop.hasHydratedSuccessfully)
                assertFalse(originalLoop === replacementLoop)
            } finally {
                repo.clearAll()
            }
        }
    }

    @Test
    fun different_conversations_get_independent_flights() = runTest {
        withContext(Dispatchers.Default) {
            val transport = FakeHydrationTransport(gated = true)
            val repo = newRepo(transport, backgroundScope)
            try {
                val callerA = async(Dispatchers.Unconfined) { repo.getOrCreate(HydrationTarget("conv-sf-a")) }
                val callerB = async(Dispatchers.Unconfined) { repo.getOrCreate(HydrationTarget("conv-sf-b")) }

                // Both conversations hydrate concurrently behind the shared
                // gate; whichever claims first signals, both finish on release.
                withTimeout(10_000) {
                    transport.firstListStarted.await()
                    transport.secondListStarted.await()
                }
                transport.releaseGate()

                callerA.await()
                callerB.await()

                // Independent conversations each perform their own hydration.
                assertEquals(2, transport.listCalls.value)
            } finally {
                repo.clearAll()
            }
        }
    }

    @Test
    fun hydration_failure_permits_later_retry() = runTest {
        withContext(Dispatchers.Default) {
            val transport = FakeHydrationTransport(failFirstCall = true)
            val repo = newRepo(transport, backgroundScope)
            try {
                val target = HydrationTarget("conv-sf-fail")
                val loop1 = repo.getOrCreate(target)
                assertFalse(loop1.hasHydratedSuccessfully)

                // The failed flight was removed — this caller starts a fresh hydrate.
                val loop2 = repo.getOrCreate(target)
                assertTrue(loop2.hasHydratedSuccessfully)
                assertEquals(2, transport.listCalls.value)
            } finally {
                repo.clearAll()
            }
        }
    }
}
