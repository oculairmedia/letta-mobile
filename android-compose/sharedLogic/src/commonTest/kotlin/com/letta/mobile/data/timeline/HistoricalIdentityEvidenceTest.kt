package com.letta.mobile.data.timeline

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalIdentityEvidenceTest {
    @Test
    fun releasedEventsStillHaveExactOwners() {
        val event = message("owner", "server", "body")
        val original = Timeline("conversation", persistentListOf(event))
        val evidence = HistoricalIdentityEvidence.checkpoint(original)
        val released = original.copy(events = persistentListOf())

        assertTrue(released.events.isEmpty())
        assertEquals(setOf("owner"), evidence.ownersFor(event))
        assertEquals(setOf("owner"), evidence.ownersFor(event.copy(otid = "replay")))
    }

    @Test
    fun sameIdGrowthFindsOwnerWithoutTreatingBodyAsUnchanged() {
        val event = message("owner", "server", "short")
        val evidence = HistoricalIdentityEvidence.checkpoint(Timeline("conversation", persistentListOf(event)))

        assertEquals(setOf("owner"), evidence.ownersFor(event.copy(content = "short and growing")))
    }

    @Test
    fun semanticMatchRetainsEveryOwner() {
        val first = message("first", "server-1", "same")
        val second = message("second", "server-2", "same").copy(position = 2.0)
        val evidence = HistoricalIdentityEvidence.checkpoint(
            Timeline("conversation", persistentListOf(first, second)),
        )

        assertEquals(setOf("first", "second"), evidence.ownersFor(message("third", "server-3", "same")))
    }

    @Test
    fun equalStringHashesDoNotMergeDistinctSemanticIdentities() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val event = message("owner", "server-1", "Aa")
        val evidence = HistoricalIdentityEvidence.checkpoint(Timeline("conversation", persistentListOf(event)))

        assertTrue(evidence.ownersFor(message("other", "server-2", "BB")).isEmpty())
        assertTrue(evidence.ownersFor(message("other", "server-2", "Aa").copy(runId = "other-run")).isEmpty())
    }

    private fun message(otid: String, serverId: String, content: String) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = otid,
        content = content,
        serverId = serverId,
        messageType = TimelineMessageType.ASSISTANT,
        date = parseTimelineInstant("1970-01-01T00:00:00Z"),
        runId = "run",
        stepId = null,
    )
}
