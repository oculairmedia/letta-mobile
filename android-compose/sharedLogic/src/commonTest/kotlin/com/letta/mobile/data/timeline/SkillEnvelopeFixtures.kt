package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.StoredToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec

/** Stored events for a skill load: its `Skill` call, its instructions as their own user message, and the run's other steps. */
internal object SkillEnvelopeFixtures {
    const val SKILL_CALL = "call-skill"
    const val RUN = "run-1"

    /**
     * letta-mobile-032ia: "too large to hold inline" follows the per-row inline limit, which moved
     * from 16 KiB to [TimelineBoundedReader.MAX_INLINE_ROW_BYTES]; the fixture stays over the limit so
     * the oversized paths it exercises are the oversized paths it tests.
     */
    val OVERSIZED = "Keep sentences short and lead with the change.\n"
        .repeat((TimelineBoundedReader.MAX_INLINE_ROW_BYTES / 46L).toInt() + 1)

    fun envelope(body: String) =
        "<skill_content name=\"release-notes\">\n---\nname: release-notes\ndescription: House style for \"release\" notes.\n---\n\n" +
            "# Release notes\n\n$body</skill_content>"

    fun message(id: Int, type: String, content: String) = StoredTimelineEvent(
        position = id.toDouble(), otid = "otid-$id", serverId = "id-$id", messageType = type,
        dateIso = "2026-11-18T09:00:0${id}Z", content = content,
    )

    /** A run's tool call with its return folded in: "Launching skill: …" for the skill, "ok" for anything else. */
    fun toolCall(id: Int, call: StoredToolCall) = message(id, "tool_call_message", "").copy(
        runId = RUN,
        toolCalls = listOf(call),
        toolReturnContentByCallId = mapOf(call.id to if (call.name == "Skill") "Launching skill: release-notes" else "ok"),
    )

    fun encoded(event: StoredTimelineEvent) =
        TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event).encodeToByteArray()

    fun key(id: Int) = TimelinePageKey(id.toLong(), TimelineMessageId("id-$id"))

    fun put(store: InMemoryTimelineStore, event: StoredTimelineEvent) {
        val key = key(event.position.toInt())
        store.rows[key] = TimelineStoredRecord(key, TIMELINE_EVENT_CONTENT_TYPE, encoded(event))
    }
}
