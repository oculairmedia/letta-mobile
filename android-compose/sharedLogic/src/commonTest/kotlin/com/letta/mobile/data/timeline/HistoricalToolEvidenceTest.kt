package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ToolCall
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoricalToolEvidenceTest {
    @Test
    fun bodyLookupReadsExactlyOneOwnerAndNeverGuesses() = kotlinx.coroutines.test.runTest {
        val event = owner()
        val checkpoint = HistoricalToolEvidence.checkpoint(Timeline("conversation", persistentListOf(event)))
        val reads = mutableListOf<String>()
        val found = checkpoint.withUniqueOwnerBody("call", "run") { key ->
            reads += key
            event
        }
        assertTrue(found is HistoricalToolEvidence.BodyLookup.Found)
        assertEquals(listOf("owner"), reads)
        reads.clear()
        val missing = checkpoint.withUniqueOwnerBody("absent", "run") { key -> reads += key; event }
        assertEquals(HistoricalToolEvidence.BodyLookup.NoOwner, missing)
        val ambiguous = HistoricalToolEvidence.checkpoint(
            Timeline("conversation", persistentListOf(event, event.copy(otid = "second", serverId = "second", position = 2.0))),
        ).withUniqueOwnerBody("call", "run") { key -> reads += key; event }
        assertEquals(HistoricalToolEvidence.BodyLookup.Ambiguous, ambiguous)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun ownerSurvivesReleaseWithoutRetainingArgumentsOrReturnBody() {
        val timeline = Timeline("conversation", persistentListOf(owner()))
        val checkpoint = HistoricalToolEvidence.checkpoint(timeline)
        val released = timeline.copy(events = persistentListOf())
        assertTrue(released.events.isEmpty())
        val evidence = checkpoint.ownersFor("call", "run").single()
        assertEquals("owner", evidence.otid)
        assertFalse(evidence.hasReturnBody)
        assertNull(evidence.truncation)
        assertTrue(checkpoint.ownersFor("call", "other-run").isEmpty())
        assertTrue(checkpoint.ownersFor("other-call", "run").isEmpty())
        assertFalse(evidence.toString().contains("secret arguments"))
    }

    @Test
    fun distinguishesPendingPreviewAndFullReturnWithoutKeepingOutput() {
        val preview = ToolReturnTruncation("return-message", 8_000_000)
        val pending = owner()
        val partial = pending.copy(
            toolReturnContentByCallId = persistentMapOf("call" to "secret output"),
            toolReturnTruncationByCallId = persistentMapOf("call" to preview),
        )
        val full = partial.copy(toolReturnTruncationByCallId = persistentMapOf())
        for (event in listOf(pending, partial, full)) {
            val evidence = HistoricalToolEvidence.checkpoint(Timeline("conversation", persistentListOf(event)))
                .ownersFor("call", "run").single()
            assertEquals("call" in event.toolReturnContentByCallId, evidence.hasReturnBody)
            assertEquals(event.toolReturnTruncationByCallId["call"], evidence.truncation)
            assertFalse(evidence.toString().contains("secret output"))
        }
    }

    @Test
    fun ambiguousOwnersAreNotSilentlyReducedToOne() {
        val first = owner()
        val second = first.copy(otid = "second", serverId = "second", position = 2.0)
        val checkpoint = HistoricalToolEvidence.checkpoint(Timeline("conversation", persistentListOf(first, second)))
        assertEquals(setOf("owner", "second"), checkpoint.ownersFor("call", "run").map { it.otid }.toSet())
    }

    private fun owner() = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "owner",
        serverId = "server",
        content = "tool",
        messageType = TimelineMessageType.TOOL_CALL,
        date = parseTimelineInstant("1970-01-01T00:00:00Z"),
        runId = "run",
        stepId = null,
        toolCalls = persistentListOf(ToolCall(id = "call", name = "Bash", arguments = "secret arguments")),
    )
}
