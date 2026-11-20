package com.letta.mobile.data.model

import com.letta.mobile.data.attachment.ImageUnavailableReason
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaSource
import com.letta.mobile.data.media.UiMediaAttachment
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audio, video and linked files come through the same attachment walk as images. The image cases
 * pin that images parse exactly as before.
 */
class MediaAttachmentParsingTest {
    private fun parts(json: String) = AssistantMessage(id = "m1", contentRaw = Json.parseToJsonElement(json)).attachments

    @Test
    fun imagesParseAsBefore() {
        val parsed = parts(
            """[{"type":"image","data":"AAAA","mimeType":"image/png"},
               {"type":"image","source":{"type":"url","url":"https://host/render"}},
               {"type":"image","source":{"type":"base64","media_type":"image/jpeg"}},
               {"type":"image_url","image_url":{"url":"data:image/gif;base64,R0lG"}}]""",
        )
        assertEquals(
            listOf(
                MessageContentPart.Image("AAAA", "image/png"),
                MessageContentPart.Image("", "image/unknown", url = "https://host/render"),
                MessageContentPart.Image("", "image/jpeg", unavailableReason = ImageUnavailableReason.Missing),
                MessageContentPart.Image("R0lG", "image/gif"),
            ),
            parsed,
        )
        assertTrue(parsed.all { it.kind == MediaKind.Image })
    }

    @Test
    fun anImagePartWithAnUninformativeTypeStaysAnImage() {
        val image = parts("""[{"type":"image","data":"AAAA","mimeType":"application/octet-stream"}]""").single()
        assertEquals(MediaKind.Image, image.kind)
        assertEquals(1, listOf(image).toJsonArray().size)
    }

    @Test
    fun mcpAudioContentIsTheFlatImageShape() {
        val audio = parts("""[{"type":"audio","data":"UklGRg==","mimeType":"audio/wav"}]""").single()
        assertEquals(MessageContentPart.Image("UklGRg==", "audio/wav"), audio)
        assertEquals(MediaKind.Audio, audio.kind)
    }

    @Test
    fun wrappedAndUrlVideoTakeTheKindFromThePartTypeWhenTheMimeSaysNothing() {
        val parsed = parts(
            """[{"type":"video","source":{"type":"url","url":"https://host/clip"}},
               {"type":"video","url":"https://host/b.webm","mimeType":"application/octet-stream"},
               {"type":"audio","source":{"type":"base64","media_type":"audio/ogg","data":"T2dn"}}]""",
        )
        assertEquals(listOf(MediaKind.Video, MediaKind.Video, MediaKind.Audio), parsed.map { it.kind })
        assertEquals("video/unknown", parsed[0].mediaType)
        assertEquals("https://host/clip", parsed[0].url)
    }

    @Test
    fun audioWithoutAPayloadIsNotAnAttachment() {
        assertEquals(emptyList(), parts("""{"type":"audio","enabled":true,"volume":3}"""))
        assertEquals(emptyList(), parts("""{"type":"video","source":{"type":"base64","media_type":"video/mp4"}}"""))
    }

    @Test
    fun openAiInputAudioAndMcpResourcesAreRead() {
        val parsed = parts(
            """[{"type":"input_audio","input_audio":{"data":"SUQz","format":"mp3"}},
               {"type":"resource_link","uri":"https://host/talk.m4a","name":"talk.m4a","mimeType":"audio/mp4"},
               {"type":"resource_link","uri":"https://host/report.pdf","mimeType":"application/pdf"},
               {"type":"resource","resource":{"uri":"file:///clip.mp4","mimeType":"video/mp4","blob":"AAAAIGZ0eXA="}}]""",
        )
        assertEquals(listOf(MediaKind.Audio, MediaKind.Audio, MediaKind.File, MediaKind.Video), parsed.map { it.kind })
        assertEquals("audio/mp3", parsed[0].mediaType)
        assertEquals("https://host/talk.m4a", parsed[1].url)
    }

    @Test
    fun resourcesThatAreNotPlayableMediaAreIgnored() {
        val parsed = parts(
            """[{"type":"resource","resource":{"uri":"file:///a.txt","mimeType":"text/plain","text":"hello"}},
               {"type":"resource","resource":{"uri":"file:///a.png","mimeType":"image/png","blob":"AAAA"}},
               {"type":"resource_link","uri":"file:///local.mp3","mimeType":"audio/mpeg"},
               {"type":"resource_link","uri":"https://host/pic.png","mimeType":"image/png"}]""",
        )
        assertEquals(emptyList(), parsed)
    }

    @Test
    fun toolReturnMediaIsOwnedByItsCall() {
        val message = ToolReturnMessage(
            id = "r1",
            toolReturnRaw = Json.parseToJsonElement("""[{"type":"audio","data":"UklGRg==","mimeType":"audio/wav"}]"""),
            toolCallId = "call-1",
        )
        assertEquals("call-1", message.attachments.single().toolCallId)
        assertEquals(MediaKind.Audio, message.attachments.single().kind)
    }

    @Test
    fun onlyImagesAreSentBack() {
        val wire = listOf(
            MessageContentPart.Image("AAAA", "image/png"),
            MessageContentPart.Image("UklGRg==", "audio/wav"),
        ).toJsonArray()
        assertEquals(1, wire.size)
    }

    @Test
    fun theUiAttachmentCarriesKindNameSizeAndSource() {
        val remote = MessageContentPart.Image("", "audio/mpeg", url = "https://host/a/voice%20note.mp3?sig=abc").toUiMediaAttachment()
        assertEquals(MediaKind.Audio, remote.kind)
        assertEquals("voice%20note.mp3", remote.displayName)
        assertEquals(MediaSource.Remote("https://host/a/voice%20note.mp3?sig=abc"), remote.playbackSource())
        val inline = MessageContentPart.Image("UklGRg==", "audio/wav").toUiMediaAttachment()
        assertEquals(6L, inline.byteSize)
        assertEquals(MediaSource.Inline("UklGRg==", "audio/wav"), inline.playbackSource())
        assertEquals(null, UiMediaAttachment("", "audio/wav", storedByteSize = 10L).playbackSource())
    }
}
