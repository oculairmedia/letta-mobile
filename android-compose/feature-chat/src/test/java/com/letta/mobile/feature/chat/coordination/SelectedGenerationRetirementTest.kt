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
            conversations = setOf("old"),
            presentations = emptyMap(),
            canonicalWriter = firstWriter,
            parent = this,
            onRetire = { events += "runtime-retired" },
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

        // The presentation's own cleanup is slow; retirement must still join it first.
        val presentationStarted = CompletableDeferred<Unit>()
        val presentationJob = launch {
            try {
                events += "start:old"
                presentationStarted.complete(Unit)
                awaitCancellation()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    delay(50)
                    events += "old-cleanup"
                }
            }
        }
        presentationStarted.await()
        owner.ready("old")
        first.writer.appendExternalTransportLocal("old", "hello", "in-flight")

        retireSelectedGeneration(
            stopPresentation = { presentationJob.cancelAndJoin() },
            pipeline = pipeline,
            owner = owner,
            runtime = first,
        )

        assertFalse(presentationJob.isActive)
        assertTrue(presentationJob.isCancelled)
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
            conversations = setOf("old"),
            presentations = emptyMap(),
            canonicalWriter = secondWriter,
            parent = this,
        )
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
