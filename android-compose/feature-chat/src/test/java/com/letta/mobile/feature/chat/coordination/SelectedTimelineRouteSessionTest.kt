package com.letta.mobile.feature.chat.coordination

import androidx.paging.PagingData
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectedTimelineRouteSessionTest {
    @Test fun deferredConversationOpensAndSendsThroughLegacyWithoutCanonicalWrites() = runTest {
        val canonicalWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val runtime = RoutingRuntime(
            generation = 1L,
            routes = mapOf("deferred" to SelectedTimelineRoute.LegacyDeferred),
            presentations = emptyMap(),
            canonicalWriter = canonicalWriter,
            legacyWriter = legacyWriter,
            parent = this,
        )
        val observer = mutableListOf<String>()
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { observer += "start:$it" },
            stopLegacyObserver = { observer += "stop" },
        )
        val decided = session.decide(runtime, "deferred", null, this, "agent", hostOpen = null)
        assertEquals(SelectedTimelineRouteSession.Presentation.LegacyDeferred, decided)
        assertTrue(runtime.opened.isEmpty())
        session.retirePresentation()
        session.activateDeferred("deferred")
        assertEquals(listOf("stop", "start:deferred"), observer)
        val owner = SelectedChatSendOwner(runtime.config, runtime.descriptor, runtime.writer, this) {
            runtime.ready(it)
        }
        owner.ready("deferred")
        runtime.writer.markExternalTransportLocalSent("deferred", "otid")
        coVerify(exactly = 1) { legacyWriter.markExternalTransportLocalSent("deferred", "otid") }
        coVerify(exactly = 0) { canonicalWriter.markExternalTransportLocalSent(any(), any()) }
        coVerify(exactly = 0) { canonicalWriter.markExternalTransportLocalSent(any(), any(), any()) }
        owner.retire()
    }

    @Test fun switchingConversationOrGenerationRetiresDeferredObserverAndRejectsStaleWrites() = runTest {
        val firstWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val secondWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val first = RoutingRuntime(
            generation = 1L,
            routes = mapOf(
                "old" to SelectedTimelineRoute.LegacyDeferred,
                "next" to SelectedTimelineRoute.LegacyDeferred,
            ),
            presentations = emptyMap(),
            canonicalWriter = mockk(relaxed = true),
            legacyWriter = firstWriter,
            parent = this,
        )
        val observer = mutableListOf<String>()
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { observer += "start:$it" },
            stopLegacyObserver = { observer += "stop" },
        )
        assertEquals(
            SelectedTimelineRouteSession.Presentation.LegacyDeferred,
            session.decide(first, "old", null, this, "agent", null),
        )
        session.retirePresentation()
        session.activateDeferred("old")
        session.retirePresentation()
        assertEquals(
            SelectedTimelineRouteSession.Presentation.LegacyDeferred,
            session.decide(first, "next", null, this, "agent", null),
        )
        session.activateDeferred("next")
        assertEquals(listOf("stop", "start:old", "stop", "start:next"), observer)

        val owner = SelectedChatSendOwner(first.config, first.descriptor, first.writer, this) { first.ready(it) }
        session.retirePresentation()
        owner.retire()
        first.retire()
        try {
            first.writer.markExternalTransportLocalSent("next", "stale")
            fail("Retired generation accepted a send")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("retired"))
        }
        coVerify(exactly = 0) { firstWriter.markExternalTransportLocalSent(any(), any()) }
        coVerify(exactly = 0) { firstWriter.markExternalTransportLocalSent(any(), any(), any()) }

        val second = RoutingRuntime(
            generation = 2L,
            routes = mapOf("next" to SelectedTimelineRoute.LegacyDeferred),
            presentations = emptyMap(),
            canonicalWriter = mockk(relaxed = true),
            legacyWriter = secondWriter,
            parent = this,
        )
        session.activateDeferred("next")
        second.writer.markExternalTransportLocalSent("next", "fresh")
        coVerify(exactly = 1) { secondWriter.markExternalTransportLocalSent("next", "fresh") }
        second.retire()
    }

    @Test fun normalizedConversationSelectsCanonicalPresentationAndCanonicalSendTogether() = runTest {
        val presentation = emptyPresentation()
        val canonicalWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val runtime = RoutingRuntime(
            generation = 3L,
            routes = mapOf("normalized" to SelectedTimelineRoute.Canonical),
            presentations = mapOf("normalized" to presentation),
            canonicalWriter = canonicalWriter,
            legacyWriter = legacyWriter,
            parent = this,
        )
        val observer = mutableListOf<String>()
        val session = SelectedTimelineRouteSession(
            startLegacyObserver = { observer += "start:$it" },
            stopLegacyObserver = { observer += "stop" },
        )
        val decided = session.decide(runtime, "normalized", "target", this, "agent", hostOpen = null)
        assertEquals(listOf("normalized"), runtime.opened)
        assertSame(presentation, (decided as SelectedTimelineRouteSession.Presentation.Canonical).value)
        session.retirePresentation()
        val owner = SelectedChatSendOwner(runtime.config, runtime.descriptor, runtime.writer, this) {
            runtime.ready(it)
        }
        owner.ready("normalized")
        runtime.writer.markExternalTransportLocalSent("normalized", "otid")
        coVerify(exactly = 1) { canonicalWriter.markExternalTransportLocalSent("normalized", "otid") }
        coVerify(exactly = 0) { legacyWriter.markExternalTransportLocalSent(any(), any()) }
        coVerify(exactly = 0) { legacyWriter.markExternalTransportLocalSent(any(), any(), any()) }
        assertEquals(listOf("stop"), observer)
        owner.retire()
        runtime.retire()
    }

    private fun emptyPresentation() = ChatPagingPresentation(
        settled = flowOf(PagingData.empty()),
        live = MutableStateFlow(emptyList()),
        close = {},
    )

    private class RoutingRuntime(
        override val generation: Long,
        private val routes: Map<String, SelectedTimelineRoute>,
        private val presentations: Map<String, ChatPagingPresentation>,
        private val canonicalWriter: TimelineExternalTransportWriter,
        private val legacyWriter: TimelineExternalTransportWriter,
        parent: CoroutineScope,
    ) : SelectedChatRuntime {
        val opened = mutableListOf<String>()
        private var retired = false
        override val config = mockk<com.letta.mobile.data.model.LettaConfig>(relaxed = true)
        override val descriptor = mockk<com.letta.mobile.runtime.BackendDescriptor>(relaxed = true)
        override val scope: CoroutineScope = parent
        override val writer: TimelineExternalTransportWriter = object : TimelineExternalTransportWriter by canonicalWriter {
            override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) {
                delegate(conversationId).markExternalTransportLocalSent(conversationId, otid)
            }

            override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) {
                delegate(conversationId).markExternalTransportLocalSent(agentId, conversationId, otid)
            }
        }

        private fun delegate(conversationId: String): TimelineExternalTransportWriter {
            check(!retired) { "Selected runtime retired" }
            return when (routes.getValue(conversationId)) {
                SelectedTimelineRoute.Canonical -> canonicalWriter
                SelectedTimelineRoute.LegacyDeferred -> legacyWriter
            }
        }

        override suspend fun ready(conversationId: String): SelectedTimelineRoute {
            check(!retired) { "Selected runtime retired" }
            return routes.getValue(conversationId)
        }

        override suspend fun open(
            conversationId: String,
            target: String?,
            scope: CoroutineScope,
        ): ChatPagingPresentation {
            check(ready(conversationId) == SelectedTimelineRoute.Canonical) {
                "Deferred conversations use the legacy observer, not canonical paging"
            }
            opened += conversationId
            return presentations.getValue(conversationId)
        }

        override suspend fun retire() {
            retired = true
        }
    }
}
