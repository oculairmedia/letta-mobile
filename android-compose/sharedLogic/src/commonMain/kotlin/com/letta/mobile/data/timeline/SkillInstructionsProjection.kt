package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.tools.bundled.SkillToolPresenter
import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/*
 * A skill's instructions on a settled page: the runtime sends them as their own hidden message just
 * after the skill's call. The page hides that message (decoded, or recognised by the head of a body
 * too large to decode) and hands it to the call that loaded the skill.
 */

/**
 * [record] with its decoded [event] (null when undecoded), and whether the page hides it: an opaque
 * body, a skill's instructions (decoded, or recognised by the head of a body too large to decode), or
 * an event [isSuppressed] says is drawn elsewhere.
 */
internal suspend fun TimelineStoreReader.projectionRecord(
    record: TimelineSettledRecord,
    event: TimelineEvent.Confirmed?,
    isSuppressed: suspend (TimelineEvent.Confirmed) -> Boolean,
): TimelineProjectionRecord {
    if (record.contentType != TIMELINE_EVENT_CONTENT_TYPE) return TimelineProjectionRecord(record, event, excluded = true)
    if (event == null) {
        val skillEnvelope = isSkillEnvelopePreview(record)
        return TimelineProjectionRecord(record, null, excluded = skillEnvelope, skillEnvelopePreview = skillEnvelope)
    }
    return TimelineProjectionRecord(record, event, excluded = event.isSyntheticSkillEnvelope() || isSuppressed(event))
}

/** Whether an undecoded event body is a skill's instructions: still hidden model context, which only its head can say. */
private suspend fun TimelineStoreReader.isSkillEnvelopePreview(record: TimelineSettledRecord): Boolean {
    val pointer = record.pointer ?: return false
    return opensSkillEnvelope(body(pointer, 0, minOf(pointer.encodedBytes, SKILL_ENVELOPE_HEAD_BYTES.toLong()).toInt()))
}

/**
 * Hands each skill's instructions to the call that loaded them. The runtime sends a skill's
 * instructions as their own hidden message just after its `Skill` call, and the page drops that
 * message (model context, not a turn); the nearest earlier skill call on the page that holds no
 * instructions yet takes them: their text when the message was decoded, or a pointer to its stored
 * body when it was too large to decode, which the call's card reads a page at a time. Runs after
 * [aggregatePreparedRuns], whose output stays aligned with [TimelinePageProjectionInput.records].
 */
internal fun TimelinePageProjectionInput.attachSkillInstructions(
    prepared: List<TimelineSettledRecord>,
): List<TimelineSettledRecord> {
    val output = prepared.toMutableList()
    val claimed = mutableListOf<UiToolCall>()
    records.forEachIndexed { index, source ->
        val text = source.event?.takeIf { it.isSyntheticSkillEnvelope() }?.content
        val stored = source.record.takeIf { source.skillEnvelopePreview }?.bodyReference(context.scope)
        if (text == null && stored == null) return@forEachIndexed
        val owner = (index - 1 downTo 0).firstNotNullOfOrNull { at ->
            val render = output[at].preparedPresentation as? TimelineSettledPresentation.Render
            render?.item?.unclaimedSkillCall(claimed)?.let { call -> Triple(at, render, call) }
        } ?: return@forEachIndexed
        val (at, render, call) = owner
        claimed += call
        output[at] = output[at].copy(preparedPresentation = render.withSkillInstructions(call, text, stored))
    }
    return output
}

private fun TimelineSettledRecord.bodyReference(scope: TimelineScope): TimelineBodyReference? =
    pointer?.let { TimelineBodyReference(scope, key, it, contentType, revision) }

/** The newest skill call in this item that has an id, no instructions yet, and is not already [claimed] on this page. */
private fun ChatRenderItem.unclaimedSkillCall(claimed: List<UiToolCall>): UiToolCall? {
    val messages = when (this) {
        is ChatRenderItem.Single -> listOf(message)
        is ChatRenderItem.RunBlock -> messages.map { it.first }
    }
    return messages.asReversed().asSequence().flatMap { it.toolCalls.orEmpty().asReversed() }.firstOrNull { call ->
        call.loadsSkill() && call.skillInstructions == null && !call.toolCallId.isNullOrBlank() &&
            claimed.none { it.toolCallId == call.toolCallId }
    }
}

private fun UiToolCall.loadsSkill(): Boolean = SkillToolPresenter.matcher.names.any { it.equals(name, ignoreCase = true) }

/** This item with [owner] (a skill call it holds, with an id) holding the instructions: their [text], or the [stored] body's pointer. */
private fun TimelineSettledPresentation.Render.withSkillInstructions(
    owner: UiToolCall,
    text: String?,
    stored: TimelineBodyReference?,
): TimelineSettledPresentation.Render {
    val key = requireNotNull(owner.toolCallId)
    return copy(
        item = if (text == null) item else item.mapToolCalls { call ->
            if (call.toolCallId == key) call.copy(skillInstructions = text) else call
        },
        skillInstructionBodies = stored?.let { skillInstructionBodies + (key to it) } ?: skillInstructionBodies,
    )
}

private fun ChatRenderItem.mapToolCalls(transform: (UiToolCall) -> UiToolCall): ChatRenderItem = when (this) {
    is ChatRenderItem.Single -> copy(message = message.copy(toolCalls = message.toolCalls?.map(transform)))
    is ChatRenderItem.RunBlock -> copy(
        messages = messages.map { (message, position) -> message.copy(toolCalls = message.toolCalls?.map(transform)) to position },
    )
}

/**
 * Whether an undecoded stored event body opens as a skill's instructions, from its first bytes:
 * its `content` string is serialized right after its position and otid, so the head carries the
 * envelope's opening tag and frontmatter. Read leniently, as the head ends mid-body.
 */
internal fun opensSkillEnvelope(head: ByteArray): Boolean {
    val text = head.decodeToString()
    val start = text.indexOf(CONTENT_FIELD)
    if (start < 0) return false
    return SyntheticSkillEnvelopeDetector.opensSkillEnvelope(jsonStringPrefix(text, start + CONTENT_FIELD.length))
}

/** The decoded opening of the JSON string starting at [from], up to its end or the first escape cut off by the head's end. */
private fun jsonStringPrefix(text: String, from: Int): String = buildString {
    var at = from
    while (at < text.length) {
        val char = text[at]
        when {
            char == '"' -> return@buildString
            char != '\\' -> append(char).also { at++ }
            at + 1 >= text.length -> return@buildString
            text[at + 1] == 'u' -> {
                if (at + UNICODE_ESCAPE > text.length) return@buildString
                val code = text.substring(at + 2, at + UNICODE_ESCAPE).toIntOrNull(HEX) ?: return@buildString
                append(code.toChar())
                at += UNICODE_ESCAPE
            }
            else -> {
                append(JSON_ESCAPES[text[at + 1]] ?: text[at + 1])
                at += 2
            }
        }
    }
}

/** How much of an undecoded body is read to tell whether it is a skill's instructions. */
internal const val SKILL_ENVELOPE_HEAD_BYTES = 2 * 1024
private const val CONTENT_FIELD = "\"content\":\""
private const val HEX = 16
private const val UNICODE_ESCAPE = 6
private val JSON_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')
