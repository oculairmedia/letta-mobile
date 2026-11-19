package com.letta.mobile.data.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaKindTest {
    @Test
    fun mimeTypeDecidesFirst() {
        assertEquals(MediaKind.Image, MediaKind.of("image/png"))
        assertEquals(MediaKind.Audio, MediaKind.of("audio/mpeg", "https://host/clip.mp4"))
        assertEquals(MediaKind.Video, MediaKind.of("VIDEO/MP4"))
        assertEquals(MediaKind.File, MediaKind.of("application/pdf"))
    }

    @Test
    fun uninformativeTypesFallBackToTheExtensionWithoutReadingTheQuery() {
        assertEquals(MediaKind.Audio, MediaKind.of("image/unknown", "https://host/a/voice.MP3?token=x.mp4"))
        assertEquals(MediaKind.Video, MediaKind.of("application/octet-stream", "https://host/movie.webm#t=3"))
        assertEquals(MediaKind.Audio, MediaKind.of("audio/unknown"))
        assertEquals(MediaKind.Video, MediaKind.of("video/unknown"))
    }

    @Test
    fun anImagePartWithNoTellingExtensionStaysAnImage() {
        assertEquals(MediaKind.Image, MediaKind.of("image/unknown", "https://host/render"))
        assertEquals(MediaKind.Image, MediaKind.of("image/unknown"))
        assertEquals(MediaKind.Image, MediaKind.of(null))
        assertEquals(MediaKind.File, MediaKind.of("application/octet-stream", "https://host/report"))
    }

    @Test
    fun extensionIgnoresDotsInDirectoriesAndQueries() {
        assertEquals(null, MediaKind.extensionOf("https://host.example/v1.2/file"))
        assertEquals("wav", MediaKind.extensionOf("https://host/a.b/c.WAV?x=y.mp3"))
        assertEquals(null, MediaKind.extensionOf(".hidden"))
    }

    @Test
    fun limitsMeasureBase64WithoutDecoding() {
        assertEquals(3L, MediaLimits.decodedBytes(4))
        assertEquals(true, MediaLimits.inlinePlayable(4))
        assertEquals(false, MediaLimits.inlinePlayable(Int.MAX_VALUE))
    }

    @Test
    fun theClockReadsMinutesAndSeconds() {
        assertEquals("0:00", formatMediaClock(-5L))
        assertEquals("1:05", formatMediaClock(65_999L))
        assertEquals("75:00", formatMediaClock(4_500_000L))
    }
}
