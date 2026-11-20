package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Media a tool card does not draw stays on the message; images keep their one owner as before. */
class ToolMediaProjectionTest {
    private val image = MessageContentPart.Image("IMG+/==", "image/png", toolCallId = "a")
    private val unownedImage = MessageContentPart.Image("UNOWNED=", "image/png")
    private val unownedAudio = MessageContentPart.Image("", "audio/mpeg", url = "https://host/a.mp3")

    @Test
    fun unownedAudioBesideAnOwnedImageStaysOnTheMessage() {
        val ui = timelineEventToUiMessage(event(listOf(image, unownedImage, unownedAudio)))!!
        assertEquals(listOf(MediaKind.Audio), ui.attachments.map { it.kind })
        assertEquals(listOf(MediaKind.Image), ui.toolCalls.orEmpty().flatMap { it.generatedImageAttachments }.map { it.kind })
    }

    @Test
    fun withNoToolImagesEveryAttachmentStaysOnTheMessage() {
        val ui = timelineEventToUiMessage(event(listOf(unownedImage, unownedAudio)))!!
        assertEquals(listOf(MediaKind.Image, MediaKind.Audio), ui.attachments.map { it.kind })
    }

    private fun event(attachments: List<MessageContentPart.Image>) = TimelineEvent.Confirmed(
        position = 1.0,
        otid = "server-msg-1",
        content = "",
        serverId = "msg-1",
        messageType = TimelineMessageType.TOOL_CALL,
        runId = null,
        stepId = null,
        date = parseTimelineInstant("2026-04-19T06:00:00Z"),
        toolCalls = listOf(
            ToolCall(id = "a", name = "Read", arguments = "{}"),
            ToolCall(id = "b", name = "Bash", arguments = "{}"),
        ).toPersistentList(),
        attachments = attachments.toPersistentList(),
    )
}
