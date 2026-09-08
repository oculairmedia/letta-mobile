package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
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
}
