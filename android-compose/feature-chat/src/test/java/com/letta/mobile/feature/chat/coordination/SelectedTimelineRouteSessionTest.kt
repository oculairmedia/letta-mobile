package com.letta.mobile.feature.chat.coordination

import androidx.paging.PagingData
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedTimelineRouteSessionTest {
    @Test fun deferredConversationOpensAndSendsThroughLegacyWithoutCanonicalWrites() = runTest {
        val canonicalWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val runtime = RoutingSelectedChatRuntime(
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

    @Test fun switchingConversationRetiresDeferredObserverBeforeNextActivate() = runTest {
        val first = RoutingSelectedChatRuntime(
            generation = 1L,
            routes = mapOf(
                "old" to SelectedTimelineRoute.LegacyDeferred,
                "next" to SelectedTimelineRoute.LegacyDeferred,
            ),
            presentations = emptyMap(),
            canonicalWriter = mockk(relaxed = true),
            legacyWriter = mockk(relaxed = true),
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
        first.retire()
    }

    @Test fun normalizedConversationSelectsCanonicalPresentationAndCanonicalSendTogether() = runTest {
        val presentation = emptyPresentation()
        val canonicalWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<TimelineExternalTransportWriter>(relaxed = true)
        val runtime = RoutingSelectedChatRuntime(
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
}
