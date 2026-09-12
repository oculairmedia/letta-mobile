package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesktopCanonicalTimelineRuntimeTest {

    @Test fun disabledRouteBuildsNoRuntimeAndTouchesNoLedgerDirectory() {
        val ledger = createTempDirectory("canonical-disabled").resolve("ledger")
        val runtime = createDesktopCanonicalTimelineRuntime(
            transport = NoTimelineTransport,
            backendId = "backend",
            ledgerDirectory = ledger,
            enabled = false,
        )
        assertNull(runtime, "the canonical route must stay dormant unless explicitly enabled")
        assertFalse(
            ledger.toFile().exists(),
            "constructing a disabled route must perform no disk IO",
        )
    }

    @Test fun enabledRouteBuildsARuntime() {
        val runtime = createDesktopCanonicalTimelineRuntime(
            transport = NoTimelineTransport,
            backendId = "backend",
            ledgerDirectory = createTempDirectory("canonical-enabled").resolve("ledger"),
            enabled = true,
        )
        assertNotNull(runtime)
    }

    /**
     * Acquiring a second owner for a scope retires the first, which would strand the presentation
     * already rendering it. Reopening the same conversation must reuse the writer instead.
     */
    @Test fun reopeningTheSameConversationReusesItsWriter() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")
        val scope = TimelineScope("backend", "conversation", "agent")

        val first = runtime.open("agent", "conversation", backgroundScope)
        val owner = assertNotNull(coordinator.current(scope))
        first.close()

        val second = runtime.open("agent", "conversation", backgroundScope)
        assertSame(owner, coordinator.current(scope), "reopening must not replace the writer")
        second.close()
    }

    @Test fun separateConversationsGetSeparateWriters() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")

        val first = runtime.open("agent", "first", backgroundScope)
        val second = runtime.open("agent", "second", backgroundScope)
        val firstOwner = assertNotNull(coordinator.current(TimelineScope("backend", "first", "agent")))
        val secondOwner = assertNotNull(coordinator.current(TimelineScope("backend", "second", "agent")))
        assertTrue(firstOwner !== secondOwner)

        first.close()
        second.close()
    }

    /** Detaching a view is not shutdown; only closing the runtime may retire a conversation writer. */
    @Test fun closingTheRuntimeRetiresEveryWriterItOpened() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")
        val scope = TimelineScope("backend", "conversation", "agent")

        val presentation = runtime.open("agent", "conversation", backgroundScope)
        presentation.close()
        assertNotNull(coordinator.current(scope), "closing a view must not retire the writer")

        runtime.close()
        assertNull(coordinator.current(scope))
    }

    @Test fun openingAfterCloseFailsRatherThanResurrectingTheLedger() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")
        runtime.close()
        assertFailsWith<IllegalStateException> {
            runtime.open("agent", "conversation", backgroundScope)
        }
    }

    @Test fun closeIsIdempotent() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")
        runtime.open("agent", "conversation", backgroundScope).close()
        runtime.close()
        runtime.close()
    }

    /**
     * A default-shim conversation is served over a rewritten loop id, so a canonical scope built
     * from its real id would index a different history. It must stay on the legacy route.
     */
    @Test fun defaultShimConversationsAreNotServedByTheCanonicalRoute() {
        val host = DesktopCanonicalTimelineHost(isEnabled = true)
        assertFalse(host.servesConversation("conv-default-abc"))
        assertTrue(host.servesConversation("conv-1234"))
    }

    /**
     * Installing an opener makes the controller skip legacy whole-history hydration, so a disabled
     * host must refuse rather than leave the transcript permanently empty.
     */
    @Test fun installingADisabledRouteFailsRatherThanEmptyingTheTranscript() = runTest {
        val controller = DesktopChatController(defaultDesktopBootstrapState(), this)
        try {
            assertFailsWith<IllegalStateException> {
                DesktopCanonicalTimelineHost(isEnabled = false).installOn(controller)
            }
            assertNull(controller.canonicalOpen)
        } finally {
            controller.close()
        }
    }

    @Test fun theLedgerDirectoryIsNotTheLegacySnapshotDirectory() {
        assertEquals(
            listOf(".letta-mobile", "timeline-ledger"),
            defaultDesktopTimelineLedgerDirectory().toList().takeLast(2).map { it.toString() },
        )
    }
}
