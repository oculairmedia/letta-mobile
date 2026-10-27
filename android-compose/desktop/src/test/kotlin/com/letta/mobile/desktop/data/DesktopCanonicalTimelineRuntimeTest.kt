package com.letta.mobile.desktop.data

import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.unavailable
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.desktop.chat.DesktopCanonicalOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DesktopCanonicalTimelineRuntimeTest {

    @Test fun theRouteBuildsARuntimeWithoutTouchingTheLedgerDirectory() {
        val ledger = createTempDirectory("canonical-runtime").resolve("ledger")
        createDesktopCanonicalTimelineRuntime(NoTimelineTransport, "backend", ledger)
        assertFalse(ledger.toFile().exists(), "constructing the route must perform no disk IO")
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

    /** A second close must not retire a writer twice, nor resurrect the map it already cleared. */
    @Test fun closeIsIdempotent() = runTest {
        val coordinator = CanonicalTimelineCoordinator(EmptyTimelineStore(), NoTimelineTransport)
        val runtime = DesktopCanonicalTimelineRuntime(coordinator, "backend")
        val scope = TimelineScope("backend", "conversation", "agent")
        runtime.open("agent", "conversation", backgroundScope).close()

        runtime.close()
        assertNull(coordinator.current(scope))
        runtime.close()
        assertNull(coordinator.current(scope))
        assertFailsWith<IllegalStateException> { runtime.open("agent", "conversation", backgroundScope) }
    }

    /**
     * A default-shim conversation is served over a rewritten loop id, so a canonical scope built
     * from its real id would index a different history. It must stay on the in-memory loop.
     */
    @Test fun defaultShimConversationsAreNotServedByTheCanonicalRoute() {
        val host = DesktopCanonicalTimelineHost()
        assertFalse(host.servesConversation("conv-default-abc"))
        assertTrue(host.servesConversation("conv-1234"))
    }

    /**
     * Reading and writing are installed together. A route that can page but not send is a
     * transcript the user cannot reply in, and nothing downstream would report that as broken.
     */
    @Test fun installingTheRouteWiresBothHistoryAndSend() = runTest {
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(),
            this,
            rosterProvider = com.letta.mobile.desktop.chat.TEST_ROSTER,
        )
        try {
            DesktopCanonicalTimelineHost().installOn(controller, install(backgroundScope))
            assertNotNull(controller.canonicalOpen)
            assertNotNull(controller.canonicalSendFor)
            assertTrue(controller.canonicalEligible("conv-1"))
            assertFalse(controller.canonicalEligible("conv-default-1"))
        } finally {
            controller.close()
        }
    }

    /** Opening history must bind ingestion even when no composer or send queue is collected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun passivePresentationReceivesRemoteTurnsWithoutLocalSend() = runTest {
        val frames = MutableSharedFlow<TransportFrameEvent>()
        val transport = object : NoOpChannelTransport() {
            override val frameEvents = frames
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this,
            rosterProvider = com.letta.mobile.desktop.chat.TEST_ROSTER,
        )
        val host = DesktopCanonicalTimelineHost(
            backendId = "passive-test",
            ledgerDirectory = createTempDirectory("passive-canonical"),
        )
        host.installOn(controller, DesktopCanonicalSendInstall(
            frameSource = transport,
            conversationRepository = { unavailable(SessionRoute.CONVERSATIONS) },
            scope = backgroundScope,
            activeConfig = { null },
            clientVersion = { "test" },
        ))
        val presentation = assertNotNull(controller.canonicalOpen).invoke(
            DesktopCanonicalOpenRequest(
                "agent-1", "conv-remote", NoTimelineTransport, backgroundScope,
            ),
        )
        try {
            runCurrent()
            assertEquals(1, frames.subscriptionCount.value, "history must attach its shared event writer")
            suspend fun publish(frame: ServerFrame) {
                frames.emit(TransportFrameEvent(frame))
                runCurrent()
            }
            suspend fun reply(id: String, turn: String, run: String, content: String) = publish(
                ServerFrame.AssistantMessage(
                    id = id, agentId = "agent-1", conversationId = "conv-remote",
                    turnId = turn, runId = run, content = content,
                    logicalMessageId = id, textSeq = 1,
                ),
            )
            // The passive observer may deliver deltas without TurnStarted.
            reply("remote-1", "turn-1", "run-1", "first remote reply")
            presentation.live.first { rows -> rows.any { it.containsMessageId("remote-1") } }
            publish(ServerFrame.TurnDone(
                id = "done-1", ts = "", turnId = "turn-1", runId = "run-1", status = "completed",
            ))
            assertTrue(presentation.live.value.any { it.containsMessageId("remote-1") },
                "terminal must retain the unpresented settlement")
            reply("remote-2", "turn-2", "run-2", "second remote reply")
            val second = presentation.live.first { rows -> rows.any { it.containsMessageId("remote-2") } }
            publish(ServerFrame.TurnDone(
                id = "late-done-1", ts = "", turnId = "turn-1", runId = "run-1", status = "completed",
            ))
            assertEquals(second, presentation.live.value, "late terminal must not clear the next remote turn")
            reply("remote-2-tail", "turn-2", "run-2", "second remote reply tail")
            presentation.live.first { rows -> rows.any { it.containsMessageId("remote-2-tail") } }
        } finally {
            presentation.close()
            controller.close()
            host.close()
        }
    }

    /** One coordinator per agent: rebuilding it would discard a turn still in flight. */
    @Test fun eachAgentKeepsOneSendCoordinator() = runTest {
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(),
            this,
            rosterProvider = com.letta.mobile.desktop.chat.TEST_ROSTER,
        )
        try {
            DesktopCanonicalTimelineHost().installOn(controller, install(backgroundScope))
            val resolve = assertNotNull(controller.canonicalSendFor)
            val first = resolve("agent-1", NoTimelineTransport)
            assertSame(first, resolve("agent-1", NoTimelineTransport))
            assertTrue(first !== resolve("agent-2", NoTimelineTransport))
        } finally {
            controller.close()
        }
    }

    /**
     * The coordinator launches a long-lived event collector, so it must be given a scope the test
     * cancels rather than the test body's own, which would otherwise never complete.
     */
    private fun install(scope: kotlinx.coroutines.CoroutineScope) = DesktopCanonicalSendInstall(
        frameSource = NoOpChannelTransport(),
        conversationRepository = { unavailable(SessionRoute.CONVERSATIONS) },
        scope = scope,
        activeConfig = { null },
        clientVersion = { "test" },
    )

    @Test fun theLedgerLivesUnderLettaMobileHome() {
        assertEquals(
            listOf(".letta-mobile", "timeline-ledger"),
            defaultDesktopTimelineLedgerDirectory().toList().takeLast(2).map { it.toString() },
        )
    }
}
