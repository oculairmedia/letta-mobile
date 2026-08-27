package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class TimelineProcessorTest {
    private val instant = parseTimelineInstant("2026-01-01T00:00:00Z")

    @Test
    fun stateIsVisibleBeforeOrderedEffectsAndAckWaitsForEffects() = runTest {
        val releaseSend = CompletableDeferred<Unit>()
        val observed = mutableListOf<String>()
        lateinit var processor: TimelineProcessor
        processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            effectHandler = { effect ->
                val local = processor.state.value.timeline.events.single() as TimelineEvent.Local
                assertEquals("client", local.otid, "state must be published before any effect")
                observed += effect::class.simpleName ?: "unknown"
                if (effect is TimelineReductionEffect.Send) releaseSend.await()
            },
        )

        val ack = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("client", "hello"), instant),
        )
        runCurrent()

        assertEquals("client", processor.state.value.timeline.events.single().otid)
        assertEquals(listOf("Send"), observed)
        assertFalse(ack.isCompleted, "ack must not complete before the blocked send effect")

        releaseSend.complete(Unit)
        val applied = assertIs<TimelineProcessorAck.Applied>(ack.await())
        assertEquals(1L, applied.sequence)
        assertEquals(listOf("Send", "EmitSyncEvent"), observed)
    }

    @Test
    fun internallyAssignedSequenceIsMonotonicAndNoOpStillCommitsSequence() = runTest {
        val processor = processor(backgroundScope)

        val first = processor.submit(
            TimelineMutation.LocalAppend(PendingSend("client", "first"), instant),
        )
        val duplicate = processor.submit(
            TimelineMutation.LocalAppend(PendingSend("client", "ignored"), instant),
        )
        val markUnknown = processor.submit(TimelineMutation.MarkLocalFailed("missing"))

        assertEquals(listOf(1L, 2L, 3L), listOf(first.sequence, duplicate.sequence, markUnknown.sequence))
        assertIs<TimelineReductionResult.NoChange>(assertIs<TimelineProcessorAck.Applied>(duplicate).result)
        assertIs<TimelineReductionResult.NoChange>(assertIs<TimelineProcessorAck.Applied>(markUnknown).result)
        assertEquals(3L, processor.state.value.lastAppliedMutationSequence)
        assertEquals("first", processor.state.value.timeline.events.single().content)
    }

    @Test
    fun streamFramePreservesAgentScopeThroughProcessorReducer() = runTest {
        val processor = processor(backgroundScope)
        processor.submit(
            TimelineMutation.StreamFrame(
                message = UserMessage("server", JsonPrimitive("body")),
                agentId = "agent-scope",
            ),
        ).appliedResultOrThrow()

        val confirmed = assertIs<TimelineEvent.Confirmed>(processor.state.value.timeline.events.single())
        assertEquals("agent-scope", confirmed.agentId)
    }

    @Test
    fun staleGenerationIsRejectedWithExactTypedReason() = runTest {
        val processor = processor(backgroundScope)
        assertIs<TimelineProcessorAck.Applied>(
            processor.submit(
                TimelineMutation.HydrateSnapshot(
                    generation = 9,
                    messages = listOf(UserMessage("new", JsonPrimitive("new"))),
                ),
            ),
        )

        val rejected = assertIs<TimelineProcessorAck.Rejected>(
            processor.submit(
                TimelineMutation.HydrateSnapshot(
                    generation = 8,
                    messages = listOf(UserMessage("old", JsonPrimitive("old"))),
                ),
            ),
        )

        assertEquals(
            TimelineProcessorRejectionReason.StaleGeneration("HydrateSnapshot", 8, 9),
            rejected.reason,
        )
        assertEquals(1L, processor.state.value.lastAppliedMutationSequence)
        assertEquals("new", (processor.state.value.timeline.events.single() as TimelineEvent.Confirmed).serverId)
    }

    @Test
    fun gracefulCloseDrainsAcceptedWorkAndRejectsLaterSubmissions() = runTest {
        val gate = CompletableDeferred<Unit>()
        var firstSend = true
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            effectHandler = { effect ->
                if (effect is TimelineReductionEffect.Send && firstSend) {
                    firstSend = false
                    gate.await()
                }
            },
        )
        val first = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("one", "one"), instant),
        )
        val second = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("two", "two"), instant),
        )
        runCurrent()

        processor.close()
        val afterClose = assertIs<TimelineProcessorAck.Rejected>(
            processor.submit(TimelineMutation.MarkLocalFailed("one")),
        )
        assertEquals(TimelineProcessorRejectionReason.Closed, afterClose.reason)

        gate.complete(Unit)
        assertIs<TimelineProcessorAck.Applied>(first.await())
        assertIs<TimelineProcessorAck.Applied>(second.await())
        processor.closeAndJoin()
        assertEquals(listOf("one", "two"), processor.state.value.timeline.events.map { it.otid })
    }

    @Test
    fun ownerCancellationFailsCurrentAndBufferedAcksWithoutHanging() = runTest {
        val ownerJob = Job()
        val ownerScope = CoroutineScope(coroutineContext + ownerJob)
        val effectEntered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = ownerScope,
            effectHandler = { effect ->
                if (effect is TimelineReductionEffect.Send) {
                    effectEntered.complete(Unit)
                    never.await()
                }
            },
        )
        val active = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("active", "active"), instant),
        )
        val buffered = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("buffered", "buffered"), instant),
        )
        effectEntered.await()

        ownerScope.cancel()

        assertEquals(
            TimelineProcessorFailureReason.Cancelled,
            assertIs<TimelineProcessorAck.Failed>(active.await()).reason,
        )
        assertEquals(
            TimelineProcessorFailureReason.Cancelled,
            assertIs<TimelineProcessorAck.Failed>(buffered.await()).reason,
        )
    }

    @Test
    fun effectFailureIsTypedAndLaterWorkStillRuns() = runTest {
        val delivered = mutableListOf<String>()
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            effectHandler = { effect ->
                if (effect is TimelineReductionEffect.Send) {
                    if (effect.pending.otid == "bad") error("synthetic effect failure")
                    delivered += effect.pending.otid
                }
            },
        )

        val failed = assertIs<TimelineProcessorAck.Failed>(
            processor.submit(
                TimelineMutation.LocalAppend(PendingSend("bad", "bad"), instant),
            ),
        )
        val reason = assertIs<TimelineProcessorFailureReason.EffectFailure>(failed.reason)
        assertEquals(0, reason.effectIndex)
        assertIs<TimelineReductionEffect.Send>(reason.effect)

        val later = assertIs<TimelineProcessorAck.Applied>(
            processor.submit(
                TimelineMutation.LocalAppend(PendingSend("good", "good"), instant),
            ),
        )
        assertEquals(2L, later.sequence)
        assertEquals(listOf("good"), delivered)
        assertEquals(listOf("bad", "good"), processor.state.value.timeline.events.map { it.otid })
    }

    @Test
    fun temporaryBridgePreservesLegacyStateAndFullLocalPayload() = runTest {
        val mutex = Mutex()
        var canonical = Timeline("conversation")
        val bridge = object : TimelineProcessorStateBridge {
            override fun synchronizeSeed(processorState: TimelineReducerState) =
                processorState.copy(timeline = canonical)

            override fun publish(state: TimelineReducerState) {
                canonical = state.timeline
            }
        }
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(canonical),
            scope = backgroundScope,
            writeMutex = mutex,
            stateBridge = bridge,
        )
        val legacy = UserMessage(
            id = "legacy-server",
            contentRaw = JsonPrimitive("legacy"),
            date = "2026-01-01T00:00:00Z",
        ).toTimelineEvent(1.0)!!
        mutex.withLock { canonical = canonical.append(legacy) }
        val image = MessageContentPart.Image(base64 = "Aa/BB==", mediaType = "image/png")

        processor.submit(
            TimelineMutation.LocalAppend(
                PendingSend("client", "new", persistentListOf(image)),
                instant,
                TimelineLocalAppendMode.OPTIMISTIC,
            ),
        ).appliedResultOrThrow()

        assertEquals("legacy-server", (canonical.events.first() as TimelineEvent.Confirmed).serverId)
        assertEquals("client", canonical.events.last().otid)
        val local = assertIs<TimelineEvent.Local>(canonical.events.last())
        assertEquals(instant, local.sentAt)
        assertEquals("client", local.otid)
        assertEquals(persistentListOf(image), local.attachments)
        assertEquals(canonical, processor.state.value.timeline)
    }

    @Test
    fun boundedMailboxRejectsOverflowWithoutSuspendingProducer() = runTest {
        val effectEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            mailboxCapacity = 1,
            effectHandler = { effect ->
                if (effect is TimelineReductionEffect.Send && effect.pending.otid == "active") {
                    effectEntered.complete(Unit)
                    release.await()
                }
            },
        )
        val active = processor.enqueue(TimelineMutation.LocalAppend(PendingSend("active", "active"), instant))
        effectEntered.await()
        val buffered = processor.enqueue(TimelineMutation.LocalAppend(PendingSend("buffered", "buffered"), instant))

        val overflow = assertIs<TimelineProcessorAck.Rejected>(
            processor.enqueue(TimelineMutation.LocalAppend(PendingSend("overflow", "overflow"), instant)).await(),
        )
        assertEquals(TimelineProcessorRejectionReason.MailboxFull(1), overflow.reason)
        assertFalse(active.isCompleted)
        assertFalse(buffered.isCompleted)

        release.complete(Unit)
        assertIs<TimelineProcessorAck.Applied>(active.await())
        assertIs<TimelineProcessorAck.Applied>(buffered.await())
        assertEquals(listOf("active", "buffered"), processor.state.value.timeline.events.map { it.otid })
    }

    @Test
    fun concurrentProducersAreLinearizedExactlyOnceInAcknowledgedOrder() = runTest {
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            mailboxCapacity = 32,
        )
        val submissions = (0 until 24).map { index ->
            async {
                val otid = "producer-$index"
                otid to assertIs<TimelineProcessorAck.Applied>(
                    processor.submit(TimelineMutation.LocalAppend(PendingSend(otid, otid), instant)),
                )
            }
        }.awaitAll()

        val acknowledgedOrder = submissions.sortedBy { it.second.sequence }.map { it.first }
        val stateOrder = processor.state.value.timeline.events.map { it.otid }
        assertEquals(acknowledgedOrder, stateOrder)
        assertEquals(24, stateOrder.toSet().size)
        assertEquals((1L..24L).toList(), submissions.map { it.second.sequence }.sorted())
    }

    @Test
    fun closeBoundaryGivesEverySubmissionOneTerminalOutcome() = runTest {
        val gate = CompletableDeferred<Unit>()
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            mailboxCapacity = 8,
            effectHandler = { effect ->
                if (effect is TimelineReductionEffect.Send && effect.pending.otid == "accepted") gate.await()
            },
        )
        val accepted = processor.enqueue(
            TimelineMutation.LocalAppend(PendingSend("accepted", "accepted"), instant),
        )
        runCurrent()

        processor.close()
        val rejected = (0 until 8).map { index ->
            processor.enqueue(TimelineMutation.MarkLocalFailed("after-close-$index"))
        }
        gate.complete(Unit)
        processor.closeAndJoin()

        assertIs<TimelineProcessorAck.Applied>(accepted.await())
        rejected.forEach { deferred ->
            assertEquals(
                TimelineProcessorRejectionReason.Closed,
                assertIs<TimelineProcessorAck.Rejected>(deferred.await()).reason,
            )
        }
        assertEquals(listOf("accepted"), processor.state.value.timeline.events.map { it.otid })
    }

    @Test
    fun failedMutationCanBeReplayedAfterRestartFromLastCommittedState() = runTest {
        var failReduction = true
        val first = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            reducer = { state, mutation ->
                if (failReduction) error("synthetic reducer failure")
                reduceProductionMutation(state, mutation)
            },
        )
        val mutation = TimelineMutation.LocalAppend(PendingSend("replay", "replay"), instant)
        assertIs<TimelineProcessorAck.Failed>(first.submit(mutation))
        assertEquals(0L, first.state.value.lastAppliedMutationSequence)
        assertTrue(first.state.value.timeline.events.isEmpty())
        first.closeAndJoin()

        failReduction = false
        val restarted = TimelineProcessor(
            initialState = first.state.value,
            scope = backgroundScope,
            reducer = { state, replayed -> reduceProductionMutation(state, replayed) },
        )
        val applied = assertIs<TimelineProcessorAck.Applied>(restarted.submit(mutation))
        assertEquals(1L, applied.sequence)
        assertEquals(listOf("replay"), restarted.state.value.timeline.events.map { it.otid })
    }

    @Test
    fun publicationFailureDoesNotExposeUncommittedProcessorState() = runTest {
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conversation")),
            scope = backgroundScope,
            stateBridge = object : TimelineProcessorStateBridge {
                override fun publish(state: TimelineReducerState) {
                    error("synthetic publication failure")
                }
            },
        )

        val failed = assertIs<TimelineProcessorAck.Failed>(
            processor.submit(TimelineMutation.LocalAppend(PendingSend("hidden", "hidden"), instant)),
        )
        assertIs<TimelineProcessorFailureReason.StatePublicationFailure>(failed.reason)
        assertEquals(0L, processor.state.value.lastAppliedMutationSequence)
        assertTrue(processor.state.value.timeline.events.isEmpty())
    }

    private fun processor(scope: CoroutineScope) = TimelineProcessor(
        initialState = TimelineReducerState(Timeline("conversation")),
        scope = scope,
    )
}
