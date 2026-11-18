package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.OVERSIZED
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.SKILL_CALL
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.envelope
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.key
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.message
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.put
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.toolCall
import com.letta.mobile.data.timeline.snapshot.StoredToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A skill's instructions arrive as their own hidden message just after its `Skill` call. A settled
 * page never shows that message as a row of its own, whatever its size: the call that loaded the skill
 * owns it, holding the text when the message decoded inline and a pointer to its stored body when it
 * was too large to (which used to surface as a separate "Message too large to show inline" card). An
 * unrelated message too large to decode keeps that fallback.
 */
class TimelineSkillInstructionsTest {
    private val scope = TimelineScope("backend", "canonical-conversation")

    @Test fun anOversizedSkillEnvelopeIsOwnedByTheCallThatLoadedIt() = runTest {
        val page = prepare(envelope(OVERSIZED))
        assertTrue(page.records.none { it.preparedPresentation is TimelineSettledPresentation.Defer }, "no held-back row")
        val render = page.renders().single()
        val calls = assertIs<ChatRenderItem.RunBlock>(render.item).messages.flatMap { it.first.toolCalls.orEmpty() }
        // The envelope stays inside the run: the skill call and the step after it are still one block.
        assertEquals(listOf("Skill", "Bash"), calls.map { it.name })
        assertEquals(key(2), render.skillInstructionBodies.getValue(SKILL_CALL).key)
        assertNull(calls.first().skillInstructions)
    }

    @Test fun aSkillEnvelopeThatDecodesTravelsAsTheCallsInstructions() = runTest {
        val content = envelope("Keep sentences short.\n".repeat(8))
        val render = prepare(content).renders().single()
        val calls = assertIs<ChatRenderItem.RunBlock>(render.item).messages.flatMap { it.first.toolCalls.orEmpty() }
        assertEquals(content, calls.single { it.name == "Skill" }.skillInstructions)
        assertTrue(render.skillInstructionBodies.isEmpty())
    }

    @Test fun anUnrelatedOversizedMessageKeepsItsFallback() = runTest {
        for (content in listOf("Paragraph. ".repeat(3_000), "name: notes\ndescription: a pasted file\n" + "Line.\n".repeat(4_000))) {
            val page = prepare(content)
            assertEquals(1, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Defer }, content.take(20))
            assertTrue(page.renders().all { it.skillInstructionBodies.isEmpty() })
        }
    }

    private fun TimelinePreparedPage.renders() = records.mapNotNull { it.preparedPresentation as? TimelineSettledPresentation.Render }

    /** A skill call, its instructions as their own user message, then the run's next step. */
    private suspend fun prepare(instructions: String): TimelinePreparedPage {
        val store = InMemoryTimelineStore()
        put(store, toolCall(1, StoredToolCall(SKILL_CALL, "Skill", """{"skill":"release-notes"}""")))
        put(store, message(2, "user_message", instructions))
        put(store, toolCall(3, StoredToolCall("call-bash", "Bash", """{"command":"ls"}""")))
        val writer = TimelineExactCanonicalWriter(scope, 64 * 1024)
        val engine = CanonicalTimelineEngine(store, writer, TimelinePageBudget(32, 64 * 1024), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        return engine.preparePage(selection, TimelineReadPosition.Tail, 32, "own-agent", DefaultTimelineSettledProjectionAdapter)
    }
}
