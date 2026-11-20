@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlaybackCoordinator
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UnsupportedMediaPlayer
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.media.LocalMediaPlaybackCoordinator
import com.letta.mobile.ui.chat.media.LocalMediaPlayerFactory
import com.letta.mobile.ui.chat.media.MediaTestTags
import com.letta.mobile.ui.chat.media.ScriptedMediaPlayer
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Audio, video and file attachments render through the rows' one entry point; images stay on the grid. */
class ChatRowMediaUiTest {
    private val image = UiMediaAttachment(base64 = TINY_PNG, mediaType = "image/png")
    private val audio = UiMediaAttachment(base64 = "", mediaType = "audio/mpeg", url = "https://media.example/voice-note.mp3")
    private val secondAudio = UiMediaAttachment(base64 = "", mediaType = "audio/wav", url = "https://media.example/reply.wav")
    private val video = UiMediaAttachment(base64 = "", mediaType = "video/mp4", url = "https://media.example/demo.mp4")
    private val players = mutableListOf<ScriptedMediaPlayer>()
    private val factory = MediaPlayerFactory { kind ->
        if (kind == MediaKind.Video) UnsupportedMediaPlayer() else ScriptedMediaPlayer().also(players::add)
    }

    @Test
    fun anImageOnlyMessageDrawsTheGridAndNoMediaCard() = runDesktopComposeUiTest(width = 900, height = 1200) {
        show(listOf(image))
        onNodeWithTag(ChatRowTestTags.IMAGE_GRID).assertExists()
        assertEquals(0, onAllNodesWithTag(MediaTestTags.AUDIO_CARD).fetchSemanticsNodes().size)
    }

    @Test
    fun aMixedMessageKeepsTheViewerOverOnlyItsImages() = runDesktopComposeUiTest(width = 900, height = 1200) {
        var opened: List<UiMediaAttachment>? = null
        show(listOf(audio, image, video), onImageTap = { images, _ -> opened = images })
        onNodeWithTag(MediaTestTags.AUDIO_CARD).assertExists()
        onNodeWithTag(MediaTestTags.VIDEO_CARD).assertExists()
        onAllNodesWithContentDescription("Attached image").onFirst().performClick()
        waitForIdle()
        assertEquals(listOf(image), opened)
    }

    @Test
    fun startingOneAudioPausesTheOtherAndDisposingReleases() = runDesktopComposeUiTest(width = 900, height = 1200) {
        var visible by mutableStateOf(true)
        setRows { if (visible) RenderRow(single(message("m", "assistant", "").copy(attachments = listOf(audio, secondAudio)))) }
        onAllNodesWithTag(MediaTestTags.PLAY_PAUSE).onFirst().performClick()
        onAllNodesWithTag(MediaTestTags.PLAY_PAUSE).onLast().performClick()
        waitForIdle()
        assertEquals(listOf(MediaPlaybackStatus.Paused, MediaPlaybackStatus.Playing), players.map { it.state.value.status })
        visible = false
        waitForIdle()
        assertTrue(players.all { it.state.value.status == MediaPlaybackStatus.Released })
    }

    @Test
    fun theTransportShowsTimeAndMutes() = runDesktopComposeUiTest(width = 360, height = 1200) {
        show(listOf(audio), host = LettaHost.Touch)
        onNodeWithTag(MediaTestTags.PLAY_PAUSE).performClick()
        players.single().advanceTo(65_000L)
        waitForIdle()
        assertEquals(1, onAllNodesWithText("1:05 / 3:20").fetchSemanticsNodes().size)
        onNodeWithTag(MediaTestTags.MUTE).performClick()
        waitForIdle()
        assertTrue(players.single().state.value.muted)
    }

    @Test
    fun bytesNotKeptDisablePlaybackWithAReason() = runDesktopComposeUiTest(width = 900, height = 1200) {
        show(listOf(UiMediaAttachment(base64 = "", mediaType = "audio/wav", storedByteSize = 4_096L)))
        onNodeWithTag(MediaTestTags.PLAY_PAUSE).assertIsNotEnabled()
        assertEquals(1, onAllNodesWithText("The file was not kept on this device").fetchSemanticsNodes().size)
        assertEquals(0, players.size)
    }

    @Test
    fun aVideoThePlatformCannotPlayKeepsItsPosterAndSaysSo() = runDesktopComposeUiTest(width = 900, height = 1200) {
        show(listOf(video))
        waitForIdle()
        assertEquals(1, onAllNodesWithText("This format does not play here").fetchSemanticsNodes().size)
        onNodeWithTag(MediaTestTags.FULLSCREEN).assertIsNotEnabled()
    }

    @Test
    fun aToolsAudioRendersInItsStep() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val call = UiToolCall(
            name = "speak", arguments = "{}", result = "ok", status = "success", toolCallId = "c1",
            generatedImageAttachments = listOf(audio),
        )
        setRows { RenderRow(single(message("m", "assistant", "").copy(toolCalls = listOf(call)))) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(MediaTestTags.AUDIO_CARD).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.show(
        attachments: List<UiMediaAttachment>,
        host: LettaHost = LettaHost.Pointer,
        onImageTap: (List<UiMediaAttachment>, Int) -> Unit = { _, _ -> },
    ) {
        val callbacks = ChatRowCallbacks(actions = RecordingChatActions(), host = ChatSurfaceHost(), onImageTap = onImageTap)
        setRows(host) { RenderRow(single(message("m", "assistant", "").copy(attachments = attachments)), callbacks = callbacks) }
    }

    private fun ComposeUiTest.setRows(host: LettaHost = LettaHost.Pointer, content: @Composable () -> Unit) {
        val coordinator = MediaPlaybackCoordinator()
        setContent {
            CompositionLocalProvider(
                LocalLettaHost provides host,
                LocalReducedMotion provides true,
                LocalMediaPlayerFactory provides factory,
                LocalMediaPlaybackCoordinator provides coordinator,
            ) {
                MaterialTheme { Box(Modifier.width(ROW_WIDTH.dp)) { content() } }
            }
        }
    }

    private companion object {
        const val ROW_WIDTH = 340
        const val TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }
}
