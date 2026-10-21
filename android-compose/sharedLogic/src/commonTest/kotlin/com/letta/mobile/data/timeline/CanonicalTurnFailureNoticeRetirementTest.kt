package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * letta-mobile-zrode: on the canonical route a failure notice stayed under every later reply. The
 * notice is client-written, never a durable row, so its settled overlay never drained, and only the
 * legacy reducer retired notices on a clean terminal. A successful turn now retires every earlier
 * notice; a failure after it still shows.
 */
class CanonicalTurnFailureNoticeRetirementTest {
    private val scope = TimelineScope("backend", "local-conv-517", "agent")

    private fun at(second: Int) = "2026-10-21T12:19:${second.toString().padStart(2, '0')}Z"

    private fun notice(key: String, second: Int) = ErrorMessage(
        id = TurnFailureNotices.noticeId(key),
        contentRaw = JsonPrimitive(TurnFailureNotices.GENERIC_MESSAGE),
        code = "other",
        date = at(second),
    )

    private fun TestScope.writer(): Pair<CanonicalTimelineCoordinator, CanonicalExternalTransportWriter> {
        val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), StaticPageTransport { emptyList() })
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, _, _ -> }, { })
        return coordinator to CanonicalExternalTransportWriter(coordinator, { _, _ -> scope }, maintenance)
    }

    private suspend fun CanonicalExternalTransportWriter.turn(run: String, clean: Boolean, vararg rows: LettaMessage) {
        turnStarted(scope.agentId, scope.conversationId, run, "turn-$run")
        rows.forEach { ingestExternalTransportMessage(scope.agentId, scope.conversationId, hostStamped(it)) }
        turnEnded(scope.agentId, scope.conversationId, clean = clean)
        clearExternalTransportActive(scope.agentId, scope.conversationId)
    }

    @Test
    fun aCleanTurnRetiresEarlierNoticesAndALaterFailureStillShows() = runTest {
        val (coordinator, writer) = writer()
        val owner = coordinator.acquire(scope)
        // A screen keeps each settled overlay resident, as the open chat does.
        val screen = assertNotNull(coordinator.attach(owner))
        val engine = owner.session.engine
        fun overlayNotices(): List<String> = (engine.settling.value + listOfNotNull(engine.live.value))
            .flatMap { it.block.events }.map { it.serverId }.filter(TurnFailureNotices::isNoticeId)

        writer.turn(
            "local-run-102", clean = false,
            UserMessage(id = "u-1", contentRaw = JsonPrimitive("hi"), date = at(1)),
            notice("turn-local-run-102", 2),
        )
        assertEquals(listOf(TurnFailureNotices.noticeId("turn-local-run-102")), overlayNotices())

        writer.turn(
            "local-run-103", clean = true,
            UserMessage(id = "u-2", contentRaw = JsonPrimitive("again"), date = at(3)),
            AssistantMessage(id = "a-2", contentRaw = JsonPrimitive("Done."), date = at(4), runId = "local-run-103"),
        )
        assertEquals(emptyList(), overlayNotices(), "the next successful turn clears the notice")

        writer.turn(
            "local-run-104", clean = false,
            UserMessage(id = "u-3", contentRaw = JsonPrimitive("once more"), date = at(5)),
            notice("turn-local-run-104", 6),
        )
        assertEquals(listOf(TurnFailureNotices.noticeId("turn-local-run-104")), overlayNotices())
        coordinator.detach(screen)
    }
}
