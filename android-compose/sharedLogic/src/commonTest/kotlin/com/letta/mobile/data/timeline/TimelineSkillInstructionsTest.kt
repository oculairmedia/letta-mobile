package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.StoredToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val render = page.records.mapNotNull { it.preparedPresentation as? TimelineSettledPresentation.Render }.single()
        val run = assertIs<ChatRenderItem.RunBlock>(render.item)
        // The envelope stays inside the run: the skill call and the step after it are still one block.
        assertEquals(listOf("Skill", "Bash"), run.messages.flatMap { it.first.toolCalls.orEmpty() }.map { it.name })
        val stored = render.skillInstructionBodies.getValue(SKILL_CALL)
        assertEquals(key(2), stored.key)
        assertNull(run.messages.flatMap { it.first.toolCalls.orEmpty() }.first().skillInstructions)
    }

    @Test fun aSkillEnvelopeThatDecodesTravelsAsTheCallsInstructions() = runTest {
        val content = envelope("Keep sentences short.\n".repeat(8))
        val render = prepare(content).records.mapNotNull { it.preparedPresentation as? TimelineSettledPresentation.Render }.single()
        val calls = assertIs<ChatRenderItem.RunBlock>(render.item).messages.flatMap { it.first.toolCalls.orEmpty() }
        assertEquals(content, calls.single { it.name == "Skill" }.skillInstructions)
        assertTrue(render.skillInstructionBodies.isEmpty())
    }

    @Test fun anUnrelatedOversizedMessageKeepsItsFallback() = runTest {
        // Frontmatter without the envelope's opening tag is not enough to hide a message read by its head.
        for (content in listOf("Paragraph. ".repeat(3_000), "name: notes\ndescription: a pasted file\n" + "Line.\n".repeat(4_000))) {
            val page = prepare(content)
            assertEquals(1, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Defer }, content.take(20))
            val render = page.records.mapNotNull { it.preparedPresentation as? TimelineSettledPresentation.Render }
            assertTrue(render.all { it.skillInstructionBodies.isEmpty() })
        }
    }

    @Test fun aHeadCutMidBodyStillReadsAsAnEnvelope() {
        val bytes = encoded(event(2, "user_message", envelope(OVERSIZED)))
        val head = bytes.copyOf(SKILL_ENVELOPE_HEAD_BYTES)
        assertTrue(opensSkillEnvelope(head))
        assertFalse(opensSkillEnvelope(encoded(event(2, "assistant_message", "Plain reply. ".repeat(300))).copyOf(SKILL_ENVELOPE_HEAD_BYTES)))
    }

    /** A skill call, its instructions as their own user message, then the run's next step. */
    private suspend fun prepare(instructions: String): TimelinePreparedPage {
        val store = InMemoryTimelineStore()
        put(store, event(1, "tool_call_message", "", StoredToolCall(SKILL_CALL, "Skill", """{"skill":"release-notes"}"""), RUN))
        put(store, event(2, "user_message", instructions))
        put(store, event(3, "tool_call_message", "", StoredToolCall("call-bash", "Bash", """{"command":"ls"}"""), RUN))
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 64 * 1024), TimelinePageBudget(32, 64 * 1024), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        return engine.preparePage(selection, TimelineReadPosition.Tail, 32, "own-agent", DefaultTimelineSettledProjectionAdapter)
    }

    private fun event(id: Int, type: String, content: String, call: StoredToolCall? = null, run: String? = null) = StoredTimelineEvent(
        position = id.toDouble(), otid = "otid-$id", serverId = "id-$id", messageType = type,
        dateIso = "2026-11-18T09:00:0${id}Z", content = content, runId = run,
        toolCalls = listOfNotNull(call),
        toolReturnContentByCallId = call?.let { mapOf(it.id to if (it.name == "Skill") "Launching skill: release-notes" else "ok") }.orEmpty(),
    )

    private fun encoded(event: StoredTimelineEvent) =
        TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event).encodeToByteArray()

    private fun put(store: InMemoryTimelineStore, event: StoredTimelineEvent) {
        val key = key(event.position.toInt())
        store.rows[key] = TimelineStoredRecord(key, TIMELINE_EVENT_CONTENT_TYPE, encoded(event))
    }

    private fun key(id: Int) = TimelinePageKey(id.toLong(), TimelineMessageId("id-$id"))

    private fun envelope(body: String) =
        "<skill_content name=\"release-notes\">\n---\nname: release-notes\ndescription: House style for \"release\" notes.\n---\n\n" +
            "# Release notes\n\n$body</skill_content>"

    private companion object {
        const val SKILL_CALL = "call-skill"
        const val RUN = "run-1"
        /** Well past the 16 KiB a page decodes inline. */
        val OVERSIZED = "Keep sentences short and lead with the change.\n".repeat(600)
    }
}
