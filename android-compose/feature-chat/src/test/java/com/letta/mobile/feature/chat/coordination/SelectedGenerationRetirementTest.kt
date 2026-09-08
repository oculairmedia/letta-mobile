package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import com.letta.mobile.feature.chat.screen.ChatPagingBinding
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectedGenerationRetirementTest {
    @Test fun delayedCleanupAndInFlightSendCannotTouchReplacementGeneration() = runTest {
        val events = mutableListOf<String>()
        val firstWriter = FakeTimelineExternalTransportWriter()
        val first = RoutingSelectedChatRuntime(
            generation = 1L,
            routes = mapOf("old" to SelectedTimelineRoute.LegacyDeferred),
            presentations = emptyMap(),
            canonicalWriter = FakeTimelineExternalTransportWriter(),
            legacyWriter = firstWriter,
            parent = this,
            onRetire = { events += "runtime-retired" },
        )
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { events += "start:$it" },
            stopLegacyObserver = {
                delay(50)
                events += "old-cleanup"
            },
        )
        val pipeline = ChatPipelineLifetime(this)
        val pipelineStarted = CompletableDeferred<Unit>()
        pipeline.scope.launch {
            try {
                pipelineStarted.complete(Unit)
                awaitCancellation()
            } finally {
                events += "pipeline-closed"
            }
        }
        val owner = SelectedChatSendOwner(first.config, first.descriptor, first.writer, this) {
            first.ready(it)
        }
        val ownerStarted = CompletableDeferred<Unit>()
        owner.scope.launch {
            try {
                ownerStarted.complete(Unit)
                awaitCancellation()
            } finally {
                events += "owner-retired"
            }
        }
        pipelineStarted.await()
        ownerStarted.await()

        val observerStarted = CompletableDeferred<Unit>()
        val observerJob = launch {
            try {
                val decided = session.decide(first, "old", null, this, "agent", null)
                assertEquals(SelectedTimelineRouteSession.Presentation.LegacyDeferred, decided)
                session.activateDeferred("old")
                observerStarted.complete(Unit)
                awaitCancellation()
            } finally {
                session.retirePresentation()
            }
        }
        observerStarted.await()
        owner.ready("old")
        first.writer.appendExternalTransportLocal("old", "hello", "in-flight")

        retireSelectedGeneration(
            stopPresentation = { observerJob.cancelAndJoin() },
            pipeline = pipeline,
            owner = owner,
            runtime = first,
        )

        assertFalse(observerJob.isActive)
        assertTrue(observerJob.isCancelled)
        assertEquals("start:old", events.first())
        assertTrue(events.indexOf("old-cleanup") < events.indexOf("pipeline-closed"))
        assertTrue(events.indexOf("pipeline-closed") < events.indexOf("owner-retired"))
        assertTrue(events.indexOf("owner-retired") < events.indexOf("runtime-retired"))
        try {
            owner.requireCurrent()
            fail("Retired send owner accepted a callback")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("retired"))
        }

        val secondWriter = FakeTimelineExternalTransportWriter()
        val second = RoutingSelectedChatRuntime(
            generation = 2L,
            routes = mapOf("old" to SelectedTimelineRoute.LegacyDeferred),
            presentations = emptyMap(),
            canonicalWriter = FakeTimelineExternalTransportWriter(),
            legacyWriter = secondWriter,
            parent = this,
        )
        session.activateDeferred("old")
        assertTrue(events.indexOf("old-cleanup") < events.lastIndexOf("start:old"))
        second.writer.appendExternalTransportLocal("old", "hello", "fresh")
        second.writer.markExternalTransportLocalSent("old", "fresh")
        assertEquals("fresh", secondWriter.externalLocals.single().otid)
        assertEquals("fresh", secondWriter.sentLocals.single().otid)
        assertEquals("in-flight", firstWriter.externalLocals.single().otid)
        assertTrue(firstWriter.sentLocals.isEmpty())
        try {
            first.writer.markExternalTransportLocalSent("old", "late-cleanup")
            fail("Delayed old generation wrote after replacement")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("retired"))
        }
        second.retire()
    }

    @Test fun conversationSwitchJoinsOldCleanupBeforeReplacementStarts() = runTest {
        val events = mutableListOf<String>()
        val first = RoutingSelectedChatRuntime(
            generation = 1L,
            routes = mapOf(
                "old" to SelectedTimelineRoute.LegacyDeferred,
                "next" to SelectedTimelineRoute.LegacyDeferred,
            ),
            presentations = emptyMap(),
            canonicalWriter = FakeTimelineExternalTransportWriter(),
            legacyWriter = FakeTimelineExternalTransportWriter(),
            parent = this,
        )
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { events += "start:$it" },
            stopLegacyObserver = {
                delay(50)
                events += "stop"
            },
        )
        val oldJob = launch {
            try {
                assertEquals(SelectedTimelineRouteSession.Presentation.LegacyDeferred, session.decide(first, "old", null, this, "agent", null))
                session.activateDeferred("old")
                awaitCancellation()
            } finally {
                session.retirePresentation()
            }
        }
        delay(10)
        oldJob.cancelAndJoin()
        session.retirePresentation()
        events += "replacement-start"
        val nextJob = launch {
            try {
                assertEquals(SelectedTimelineRouteSession.Presentation.LegacyDeferred, session.decide(first, "next", null, this, "agent", null))
                session.activateDeferred("next")
                awaitCancellation()
            } finally {
                session.retirePresentation()
            }
        }
        delay(10)
        assertTrue(nextJob.isActive)
        assertEquals(listOf("start:old", "stop", "replacement-start", "start:next"), events.take(4))
        nextJob.cancelAndJoin()
        first.retire()
    }

    @Test fun teardownAbandonDoesNotJoinAndLateCleanupCannotStopReplacement() = runTest {
        val events = mutableListOf<String>()
        val first = RoutingSelectedChatRuntime(
            generation = 1L,
            routes = mapOf("old" to SelectedTimelineRoute.LegacyDeferred),
            presentations = emptyMap(),
            canonicalWriter = FakeTimelineExternalTransportWriter(),
            legacyWriter = FakeTimelineExternalTransportWriter(),
            parent = this,
        )
        val releaseOld = CompletableDeferred<Unit>()
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { events += "start:$it" },
            stopLegacyObserver = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    events += "old-cleanup-entered"
                    releaseOld.await()
                    events += "old-cleanup"
                }
            },
        )
        val oldJob = launch {
            try {
                session.decide(first, "old", null, this, "agent", null)
                session.activateDeferred("old")
                awaitCancellation()
            } finally {
                session.retirePresentation()
            }
        }
        delay(10)
        oldJob.cancel()
        events += "abandoned"
        val replacement = launch {
            try {
                session.decide(first, "old", null, this, "agent", null)
                session.activateDeferred("next")
                awaitCancellation()
            } finally {
                events += "replacement-stopped"
            }
        }
        delay(10)
        assertTrue(replacement.isActive)
        releaseOld.complete(Unit)
        oldJob.join()
        assertTrue(replacement.isActive)
        assertEquals("start:old", events.first())
        assertTrue(events.indexOf("abandoned") < events.indexOf("old-cleanup"))
        assertFalse(events.contains("replacement-stopped"))
        replacement.cancelAndJoin()
        first.retire()
    }

    @Test fun delayedRetryAndTailOfOldPresentationDoNotJoinReplacement() {
        val binding = ChatPagingBinding()
        val created = mutableListOf<ChatPagingPresentation>()
        var published: ChatPagingPresentation? = null
        fun create(target: String?) = ChatPagingPresentation(
            kotlinx.coroutines.flow.flowOf(androidx.paging.PagingData.empty()),
            kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
            {},
        ).also { created += it }
        val first = binding.selectRoute("a", 1, null, { published = it }, ::create)
        val replacement = binding.selectRoute("a", 2, null, { published = it }, ::create)
        first.retryOpen()
        first.requestTail()
        org.junit.Assert.assertSame(replacement, binding.presentation)
        org.junit.Assert.assertEquals(2, created.size)
        replacement.requestTail()
        org.junit.Assert.assertNotSame(replacement, published)
        org.junit.Assert.assertEquals(3, created.size)
    }
}
